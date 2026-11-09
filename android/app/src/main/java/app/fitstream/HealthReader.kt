package app.fitstream

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.aggregate.AggregateMetric
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong
import kotlin.reflect.KClass

/**
 * Reads Health Connect and builds the /api/sync payload.
 *
 * Steps, distance and heart rate are sent as per-day aggregates computed by Health Connect, which also
 * de-duplicates overlapping sources (for example the phone's own step counter and the Fit3).
 * Raw per-sample heart rate is never uploaded.
 */
class HealthReader(private val client: HealthConnectClient, private val zone: ZoneId = ZoneId.systemDefault()) {

    companion object {
        val RECORD_TYPES: List<KClass<out Record>> = listOf(
            StepsRecord::class,
            DistanceRecord::class,
            HeartRateRecord::class,
            RestingHeartRateRecord::class,
            SleepSessionRecord::class,
            ExerciseSessionRecord::class,
            OxygenSaturationRecord::class,
            ActiveCaloriesBurnedRecord::class,
            TotalCaloriesBurnedRecord::class,
            WeightRecord::class,
            BodyFatRecord::class,
            BasalMetabolicRateRecord::class,
        )

        val READ_PERMISSIONS: Set<String> = RECORD_TYPES.map { HealthPermission.getReadPermission(it) }.toSet()
        val WRITE_PERMISSIONS: Set<String> = setOf(HealthPermission.getWritePermission(WeightRecord::class))
        val ALL_PERMISSIONS: Set<String> = READ_PERMISSIONS + WRITE_PERMISSIONS

        /** Read permissions plus write weight and background/history access where this Health Connect version supports them. */
        fun permissionsToRequest(client: HealthConnectClient): Set<String> {
            val perms = ALL_PERMISSIONS.toMutableSet()
            if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            ) perms += HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
            if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            ) perms += HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
            return perms
        }

        private const val MAX_ROWS = 4000

        private val EXERCISE_NAMES = mapOf(
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "walking",
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "running",
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "treadmill",
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "cycling",
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "indoor_cycling",
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "hiking",
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "pool_swim",
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "open_water_swim",
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "strength_training",
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "weightlifting",
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS to "calisthenics",
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "hiit",
            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to "elliptical",
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to "rowing_machine",
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to "stair_machine",
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "yoga",
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES to "pilates",
            ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING to "stretching",
            ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to "badminton",
            ExerciseSessionRecord.EXERCISE_TYPE_CRICKET to "cricket",
            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to "football",
            ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL to "basketball",
            ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to "tennis",
            ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS to "table_tennis",
            ExerciseSessionRecord.EXERCISE_TYPE_DANCING to "dancing",
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "other",
        )

        fun exerciseName(type: Int): String = EXERCISE_NAMES[type] ?: "type_$type"
    }

    private lateinit var granted: Set<String>
    private fun has(type: KClass<out Record>) = HealthPermission.getReadPermission(type) in granted

    suspend fun buildPayload(since: Instant, until: Instant): JSONObject {
        granted = client.permissionController.getGrantedPermissions()
        if (READ_PERMISSIONS.none { it in granted }) throw SecurityException("no Health Connect read permissions granted")

        // Start at local midnight so every day in the window is re-aggregated in full.
        val firstDay = since.atZone(zone).toLocalDate()
        val start = firstDay.atStartOfDay(zone).toInstant()
        val dayRange = TimeRangeFilter.between(firstDay.atStartOfDay(), LocalDateTime.ofInstant(until, zone))

        return JSONObject()
            .put("steps", JSONArray(steps(dayRange).take(MAX_ROWS)))
            .put("heart_rate", JSONArray(heartRate(dayRange).take(MAX_ROWS)))
            .put("sleep", JSONArray(sleep(start, until).take(MAX_ROWS)))
            .put("workouts", JSONArray(workouts(start, until).take(MAX_ROWS)))
            .put("readings", JSONArray(readings(start, until).takeLast(MAX_ROWS)))
    }

    private suspend fun daily(metrics: Set<AggregateMetric<*>>, range: TimeRangeFilter) =
        client.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(metrics, range, Period.ofDays(1)))

    private suspend fun steps(range: TimeRangeFilter): Collection<JSONObject> {
        if (!has(StepsRecord::class)) return emptyList()
        val days = sortedMapOf<LocalDate, JSONObject>()
        for (g in daily(setOf(StepsRecord.COUNT_TOTAL), range)) {
            val count = g.result[StepsRecord.COUNT_TOTAL] ?: continue
            val day = g.startTime.toLocalDate()
            days[day] = JSONObject().put("date", day.toString()).put("count", count)
        }
        if (has(DistanceRecord::class)) {
            for (g in daily(setOf(DistanceRecord.DISTANCE_TOTAL), range)) {
                val meters = g.result[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: continue
                days[g.startTime.toLocalDate()]?.put("distance_meters", round1(meters))
            }
        }
        return days.values
    }

    private suspend fun heartRate(range: TimeRangeFilter): List<JSONObject> {
        if (!has(HeartRateRecord::class)) return emptyList()
        val metrics = setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX)
        return daily(metrics, range).mapNotNull { g ->
            val avg = g.result[HeartRateRecord.BPM_AVG] ?: return@mapNotNull null
            JSONObject()
                .put("date", g.startTime.toLocalDate().toString())
                .put("avg_bpm", avg)
                .put("min_bpm", g.result[HeartRateRecord.BPM_MIN])
                .put("max_bpm", g.result[HeartRateRecord.BPM_MAX])
        }
    }

    private suspend fun sleep(start: Instant, end: Instant): List<JSONObject> {
        if (!has(SleepSessionRecord::class)) return emptyList()
        return readAll(SleepSessionRecord::class, start, end).map { s ->
            val minutes = mutableMapOf("awake" to 0.0, "light" to 0.0, "deep" to 0.0, "rem" to 0.0)
            for (stage in s.stages) {
                val key = when (stage.stage) {
                    SleepSessionRecord.STAGE_TYPE_AWAKE,
                    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "awake"
                    SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
                    SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
                    SleepSessionRecord.STAGE_TYPE_REM -> "rem"
                    else -> null
                } ?: continue
                minutes[key] = minutes.getValue(key) + minutesBetween(stage.startTime, stage.endTime)
            }
            JSONObject()
                .put("start_time", iso(s.startTime))
                .put("end_time", iso(s.endTime))
                // A night is attributed to the morning it ends on.
                .put("date", s.endTime.atZone(zone).toLocalDate().toString())
                .put("duration_min", round1(minutesBetween(s.startTime, s.endTime)))
                .apply {
                    if (s.stages.isNotEmpty()) minutes.forEach { (k, v) -> put("${k}_min", round1(v)) }
                }
        }
    }

    private suspend fun workouts(start: Instant, end: Instant): List<JSONObject> {
        if (!has(ExerciseSessionRecord::class)) return emptyList()
        val metrics = buildSet<AggregateMetric<*>> {
            if (has(ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (has(TotalCaloriesBurnedRecord::class)) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (has(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
            if (has(HeartRateRecord::class)) { add(HeartRateRecord.BPM_AVG); add(HeartRateRecord.BPM_MAX) }
        }
        return readAll(ExerciseSessionRecord::class, start, end).map { w ->
            val agg = if (metrics.isEmpty()) null
            else client.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(w.startTime, w.endTime)))
            val kcal = agg?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories
                ?: agg?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories
            JSONObject()
                .put("start_time", iso(w.startTime))
                .put("end_time", iso(w.endTime))
                .put("date", w.startTime.atZone(zone).toLocalDate().toString())
                .put("type", exerciseName(w.exerciseType))
                .put("duration_min", round1(minutesBetween(w.startTime, w.endTime)))
                .put("calories", kcal?.let(::round1))
                .put("distance_km", agg?.get(DistanceRecord.DISTANCE_TOTAL)?.inKilometers?.let { round2(it) })
                .put("avg_hr", agg?.get(HeartRateRecord.BPM_AVG))
                .put("max_hr", agg?.get(HeartRateRecord.BPM_MAX))
        }
    }

    private suspend fun readings(start: Instant, end: Instant): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        // SpO2 and resting HR can arrive many times a night, so they are collapsed to one daily average.
        if (has(OxygenSaturationRecord::class)) {
            readAll(OxygenSaturationRecord::class, start, end)
                .groupBy { it.time.atZone(zone).toLocalDate() }
                .forEach { (day, rs) -> out += dailyReading("spo2", day, "percent", round1(rs.map { it.percentage.value }.average())) }
        }
        if (has(RestingHeartRateRecord::class)) {
            readAll(RestingHeartRateRecord::class, start, end)
                .groupBy { it.time.atZone(zone).toLocalDate() }
                .forEach { (day, rs) -> out += dailyReading("resting_hr", day, "bpm", round1(rs.map { it.beatsPerMinute.toDouble() }.average())) }
        }
        if (has(WeightRecord::class)) readAll(WeightRecord::class, start, end).forEach {
            out += reading("weight", it.time, "kg", round2(it.weight.inKilograms))
        }
        if (has(BodyFatRecord::class)) readAll(BodyFatRecord::class, start, end).forEach {
            out += reading("body_fat", it.time, "percent", round1(it.percentage.value))
        }
        if (has(BasalMetabolicRateRecord::class)) readAll(BasalMetabolicRateRecord::class, start, end).forEach {
            out += reading("bmr", it.time, "kcal_per_day", round1(it.basalMetabolicRate.inKilocaloriesPerDay))
        }
        return out.sortedBy { it.getString("time") }
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, start: Instant, end: Instant): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(type, TimeRangeFilter.between(start, end), pageSize = 1000, pageToken = token)
            )
            out += resp.records
            token = resp.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }

    // Daily readings use the local calendar date as a fixed label so the key stays stable if the phone changes time zone.
    private fun dailyReading(metric: String, day: LocalDate, key: String, value: Double) =
        JSONObject().put("metric", metric).put("time", "${day}T00:00:00Z").put("values", JSONObject().put(key, value))

    private fun reading(metric: String, time: Instant, key: String, value: Double) =
        JSONObject().put("metric", metric).put("time", iso(time)).put("values", JSONObject().put(key, value))

    private fun iso(t: Instant) = t.truncatedTo(ChronoUnit.SECONDS).toString()
    private fun minutesBetween(a: Instant, b: Instant) = Duration.between(a, b).seconds / 60.0
    private fun round1(v: Double) = (v * 10).roundToLong() / 10.0
    private fun round2(v: Double) = (v * 100).roundToLong() / 100.0
}

package app.fitstream

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Reads the window since the last successful sync from Health Connect and uploads it. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = Settings(applicationContext)
        if (!settings.isConfigured) return fail(settings, "Server URL or API key not set")
        if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) {
            return fail(settings, "Health Connect is not available on this device")
        }

        val now = Instant.now()
        // Re-send the last couple of days: the band syncs to Samsung Health late, and upserts make re-sends harmless.
        val since = settings.lastSync?.minus(Duration.ofDays(OVERLAP_DAYS)) ?: now.minus(Duration.ofDays(INITIAL_DAYS))

        return try {
            val client = HealthConnectClient.getOrCreate(applicationContext)
            val payload = HealthReader(client).buildPayload(since, now)
            payload.put("last_sync", now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString())
            val counts = Uploader.post(settings.serverUrl, settings.apiKey, payload).optJSONObject("counts")
            settings.lastSync = now
            val summary = counts?.keys()?.asSequence()?.joinToString(", ") { "$it ${counts.optInt(it)}" }.orEmpty()
            settings.lastStatus = "Synced ${format(now)} ($summary)"
            Result.success()
        } catch (e: Uploader.HttpException) {
            // 4xx means bad key or a payload the server rejects; retrying will not help. 429 and 5xx are transient.
            if (e.code in 400..499 && e.code != 429) fail(settings, e.message) else retry(settings, e.message)
        } catch (e: SecurityException) {
            fail(settings, "Health Connect permission missing. Open FitStream and grant access (background sync needs the background permission).")
        } catch (e: IOException) {
            retry(settings, "Network error: ${e.message}")
        } catch (e: IllegalStateException) {
            // Thrown by Health Connect for rate limits and service binding failures.
            retry(settings, "Health Connect error: ${e.message}")
        } catch (e: android.os.RemoteException) {
            retry(settings, "Health Connect error: ${e.message}")
        }
    }

    private fun fail(settings: Settings, message: String?): Result {
        settings.lastStatus = "Failed ${format(Instant.now())}: $message"
        return Result.failure()
    }

    private fun retry(settings: Settings, message: String?): Result {
        settings.lastStatus = "Retrying (attempt ${runAttemptCount + 1}) ${format(Instant.now())}: $message"
        return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        const val PERIODIC = "fitstream-periodic"
        const val ONE_TIME = "fitstream-now"
        private const val INITIAL_DAYS = 30L
        private const val OVERLAP_DAYS = 2L
        private const val MAX_ATTEMPTS = 5

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        private val formatter = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())
        private fun format(t: Instant) = formatter.format(t)

        fun schedulePeriodic(context: Context, hours: Long) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(hours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

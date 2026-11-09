package app.fitstream

import android.content.Context
import java.time.Instant

/** App settings in private SharedPreferences. Backups are disabled in the manifest so the API key stays on the device. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("fitstream", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_URL, v.trim().trimEnd('/')).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API, "") ?: ""
        set(v) = prefs.edit().putString(KEY_API, v.trim()).apply()

    var intervalHours: Long
        get() = prefs.getLong(KEY_INTERVAL, 12L)
        set(v) = prefs.edit().putLong(KEY_INTERVAL, v).apply()

    /** End of the last successfully uploaded window. Only advanced after the server accepts a sync. */
    var lastSync: Instant?
        get() = prefs.getString(KEY_LAST_SYNC, null)?.let(Instant::parse)
        set(v) = prefs.edit().putString(KEY_LAST_SYNC, v?.toString()).apply()

    var lastStatus: String
        get() = prefs.getString(KEY_STATUS, "Never synced") ?: ""
        set(v) = prefs.edit().putString(KEY_STATUS, v).apply()

    var lastWeight: Float
        get() = prefs.getFloat(KEY_LAST_WEIGHT, 0f)
        set(v) = prefs.edit().putFloat(KEY_LAST_WEIGHT, v).apply()

    var lastWeightTime: Instant?
        get() = prefs.getString(KEY_LAST_WEIGHT_TIME, null)?.let(Instant::parse)
        set(v) = prefs.edit().putString(KEY_LAST_WEIGHT_TIME, v?.toString()).apply()

    var scaleMac: String
        get() = prefs.getString(KEY_SCALE_MAC, "A8:0B:6B:9A:BB:0A") ?: "A8:0B:6B:9A:BB:0A"
        set(v) = prefs.edit().putString(KEY_SCALE_MAC, v.trim()).apply()

    val isConfigured: Boolean
        get() = isValidUrl(serverUrl) && apiKey.length >= 32

    companion object {
        private const val KEY_URL = "server_url"
        private const val KEY_API = "api_key"
        private const val KEY_INTERVAL = "interval_hours"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_STATUS = "last_status"
        private const val KEY_LAST_WEIGHT = "last_weight"
        private const val KEY_LAST_WEIGHT_TIME = "last_weight_time"
        private const val KEY_SCALE_MAC = "scale_mac"

        /** HTTPS only: the payload is health data and the request carries the API key. */
        fun isValidUrl(url: String): Boolean {
            val u = url.trim()
            return u.startsWith("https://") && u.length > "https://".length && ' ' !in u
        }
    }
}

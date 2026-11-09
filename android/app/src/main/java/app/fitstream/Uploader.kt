package app.fitstream

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Uploader {
    class HttpException(val code: Int, message: String) : IOException(message)

    /** POSTs the payload to {baseUrl}/api/sync. Throws [HttpException] on non-2xx and [IOException] on network errors. */
    fun post(baseUrl: String, apiKey: String, payload: JSONObject): JSONObject {
        require(Settings.isValidUrl(baseUrl)) { "server URL must start with https://" }
        val conn = URL("$baseUrl/api/sync").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false // never resend the API key to another host
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-API-Key", apiKey)
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText().take(4000) }.orEmpty()
            if (code !in 200..299) {
                val reason = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty().ifEmpty { body.take(200) }
                throw HttpException(code, "HTTP $code: $reason")
            }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }
}

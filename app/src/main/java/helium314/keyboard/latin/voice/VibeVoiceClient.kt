// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * HTTP client for the VibeVoice ASR server.
 *
 * Sends a WAV file via multipart/form-data and streams back SSE transcription
 * events. The server uses a self-signed TLS certificate so we configure a
 * permissive trust manager scoped only to this connection.
 */
class VibeVoiceClient(
    private val serverUrl: String,
    private val authToken: String
) {

    companion object {
        private const val TAG = "VibeVoiceClient"
        private const val BOUNDARY = "----VibeVoiceBoundary9f2e4d"

        private val json = Json { ignoreUnknownKeys = true }

        /** Returns true if the server URL and auth token are configured (non-blank). */
        @JvmStatic
        fun isConfigured(context: Context): Boolean {
            val prefs = context.prefs()
            val url = prefs.getString(Settings.PREF_VIBEVOICE_SERVER_URL, Defaults.PREF_VIBEVOICE_SERVER_URL) ?: ""
            val token = prefs.getString(Settings.PREF_VIBEVOICE_AUTH_TOKEN, Defaults.PREF_VIBEVOICE_AUTH_TOKEN) ?: ""
            return url.isNotBlank() && token.isNotBlank()
        }

        /** Creates an instance from shared preferences, or null if not configured. */
        @JvmStatic
        fun fromPreferences(context: Context): VibeVoiceClient? {
            val prefs = context.prefs()
            val url = prefs.getString(Settings.PREF_VIBEVOICE_SERVER_URL, Defaults.PREF_VIBEVOICE_SERVER_URL) ?: ""
            val token = prefs.getString(Settings.PREF_VIBEVOICE_AUTH_TOKEN, Defaults.PREF_VIBEVOICE_AUTH_TOKEN) ?: ""
            if (url.isBlank() || token.isBlank()) return null
            return VibeVoiceClient(url.trimEnd('/'), token)
        }
    }

    private val transcribeUrl = "$serverUrl/v1/transcribe"
    private val healthUrl = "$serverUrl/health"

    @Serializable
    data class Segment(
        val Start: Double = 0.0,
        val End: Double = 0.0,
        val Content: String = ""
    )

    data class TranscriptionResult(
        val text: String,
        val segments: List<Segment>
    )

    /**
     * Transcribe a WAV file. This blocks the calling thread.
     *
     * @param audioFile The recorded WAV file to transcribe.
     * @param onPartialText Called on the IO thread with accumulated raw text as it streams in.
     * @return The parsed transcription result, or null if the request failed.
     */
    fun transcribe(
        audioFile: File,
        onPartialText: ((String) -> Unit)? = null
    ): TranscriptionResult? {
        var connection: HttpsURLConnection? = null
        try {
            connection = (URL(transcribeUrl).openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = createTrustAllSslContext().socketFactory
                hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $authToken")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
                setChunkedStreamingMode(0)
                connectTimeout = 10_000
                readTimeout = 60_000
            }

            writeMultipartBody(connection.outputStream, audioFile)

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "unknown error"
                Log.e(TAG, "Server returned $responseCode: $error")
                return null
            }

            return readSseStream(connection, onPartialText)

        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            return null
        } finally {
            connection?.disconnect()
        }
    }

    /** Quick health check — returns true if the server is reachable and healthy. */
    fun isHealthy(): Boolean {
        return try {
            val connection = (URL(healthUrl).openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = createTrustAllSslContext().socketFactory
                hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            val ok = connection.responseCode == 200
            connection.disconnect()
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Health check failed", e)
            false
        }
    }

    private fun writeMultipartBody(output: OutputStream, audioFile: File) {
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("--$BOUNDARY\r\n")
            writer.write("Content-Disposition: form-data; name=\"audio\"; filename=\"${audioFile.name}\"\r\n")
            writer.write("Content-Type: audio/wav\r\n")
            writer.write("\r\n")
            writer.flush()

            audioFile.inputStream().use { it.copyTo(output) }
            output.flush()

            writer.write("\r\n")
            writer.write("--$BOUNDARY--\r\n")
            writer.flush()
        }
    }

    private fun readSseStream(
        connection: HttpsURLConnection,
        onPartialText: ((String) -> Unit)?
    ): TranscriptionResult? {
        val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
        val accumulated = StringBuilder()

        reader.use {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                when {
                    l.startsWith("data: ") -> {
                        val payload = l.removePrefix("data: ")
                        val textValue = extractTextField(payload)
                        if (textValue != null) {
                            accumulated.append(textValue)
                            onPartialText?.invoke(accumulated.toString())
                        }
                    }
                    l.startsWith("event: done") -> break
                }
            }
        }

        return parseTranscriptionJson(accumulated.toString())
    }

    /**
     * Extract the "text" field from SSE data using kotlinx.serialization.
     * Each SSE data line is a JSON object like: {"text":"chunk"}
     */
    private fun extractTextField(payload: String): String? {
        return try {
            val obj = json.decodeFromString<JsonObject>(payload)
            obj["text"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse SSE chunk: $payload", e)
            null
        }
    }

    /**
     * Parse the accumulated JSON which is a JSON array of segments:
     * [{"Start":0,"End":2.5,"Content":"Hello world"},...]
     *
     * Filters out silence markers like [Silence] and concatenates spoken content.
     */
    private fun parseTranscriptionJson(raw: String): TranscriptionResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "[]") {
            return TranscriptionResult("", emptyList())
        }

        return try {
            val segments = json.decodeFromString<List<Segment>>(trimmed)
            val textParts = segments
                .map { it.Content }
                .filter { !it.startsWith("[") || !it.endsWith("]") }
            val fullText = textParts.joinToString(" ").trim()
            TranscriptionResult(fullText, segments)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse transcription JSON: $trimmed", e)
            TranscriptionResult("", emptyList())
        }
    }

    private fun createTrustAllSslContext(): SSLContext {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        return SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
    }
}

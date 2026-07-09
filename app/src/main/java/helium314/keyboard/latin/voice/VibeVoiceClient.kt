// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.protectedPrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Client for the VVV public API.
 *
 * The server is intentionally exposed through exactly one public mode:
 * TLS 1.3, exact pinned server public-key trust, mandatory client certificate
 * authentication, and no application-layer authentication fallback.
 */
class VibeVoiceClient private constructor(
    private val config: ClientConfig
) {

    companion object {
        private const val TAG = "VibeVoiceClient"
        private const val BOUNDARY = "----VibeVoiceBoundary9f2e4d"
        private const val TLS_PROTOCOL = "TLSv1.3"
        private const val SERVER_PIN_PREFIX = "sha256/"
        private const val CLIENT_AUTH_EKU = "1.3.6.1.5.5.7.3.2"
        private const val MAX_SSE_LINE_BYTES = 64 * 1024
        private const val MAX_SSE_EVENT_CHARS = 256 * 1024
        private const val MAX_TRANSCRIPT_CHARS = 1_000_000
        private val legacyPreferenceKeys = setOf(
            "vibevoice_server_url",
            "vibevoice_auth_token",
            "vibevoice_server_certificate",
            "vibevoice_client_certificate",
            "vibevoice_client_private_key",
            "vvv_public_api_v1_server_url",
            "vvv_public_api_v1_auth_token",
            "vvv_public_api_v1_server_certificate",
            "vvv_public_api_v1_client_certificate",
            "vvv_public_api_v1_client_private_key",
            "vvv_public_api_v2_server_url",
            "vvv_public_api_v2_auth_token",
            "vvv_public_api_v2_server_spki_pin",
            "vvv_public_api_v2_client_certificate",
            "vvv_public_api_v2_client_private_key",
        )

        private val json = Json { ignoreUnknownKeys = true }

        /** Removes older VibeVoice public API configurations so stale state is never reused. */
        @JvmStatic
        fun forgetLegacyConfiguration(context: Context) {
            legacyPreferenceStores(context).forEach { prefs ->
                if (legacyPreferenceKeys.none { prefs.contains(it) }) return@forEach
                prefs.edit().apply {
                    legacyPreferenceKeys.forEach(::remove)
                }.commit()
            }
        }

        /** Returns true only when all VVV public API credentials are present and well formed. */
        @JvmStatic
        fun isConfigured(context: Context): Boolean = loadConfig(context) != null

        /** Creates an authenticated client from credential-protected preferences. */
        @JvmStatic
        fun fromPreferences(context: Context): VibeVoiceClient? = loadConfig(context)?.let(::VibeVoiceClient)

        fun normalizeServerUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            val host = uri.host ?: return null
            if (host.isBlank() || uri.rawUserInfo != null) return null
            if (host.contains(":")) return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null
            if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
            return "https://${uri.rawAuthority}"
        }

        fun normalizeServerSpkiPin(raw: String): String? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith(SERVER_PIN_PREFIX)) return null
            val encoded = trimmed.removePrefix(SERVER_PIN_PREFIX)
            val decoded = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
                ?: return null
            if (decoded.size != 32) return null
            return SERVER_PIN_PREFIX + Base64.encodeToString(decoded, Base64.NO_WRAP)
        }

        fun isServerSpkiPin(raw: String): Boolean = normalizeServerSpkiPin(raw) != null

        fun serverSpkiPinForCertificatePem(raw: String): String? =
            runCatching { serverSpkiPin(parseCertificate(raw)) }.getOrNull()

        internal fun parseCertificateForTest(raw: String): X509Certificate = parseCertificate(raw)

        fun isCertificatePem(raw: String): Boolean =
            runCatching { parseCertificate(raw) }.isSuccess

        fun isPrivateKeyPem(raw: String): Boolean =
            runCatching { parseEcPkcs8PrivateKey(raw) }.isSuccess

        fun normalizePem(raw: String): String =
            raw.trim().replace("\r\n", "\n").replace('\r', '\n') + "\n"

        private fun loadConfig(context: Context): ClientConfig? {
            forgetLegacyConfiguration(context)
            val prefs = context.protectedPrefs()
            val serverUrl = normalizeServerUrl(
                prefs.getString(
                    Settings.PREF_VIBEVOICE_SERVER_URL,
                    Defaults.PREF_VIBEVOICE_SERVER_URL
                ) ?: ""
            ) ?: return null
            val serverSpkiPin = normalizeServerSpkiPin(
                prefs.getString(
                    Settings.PREF_VIBEVOICE_SERVER_SPKI_PIN,
                    Defaults.PREF_VIBEVOICE_SERVER_SPKI_PIN
                ) ?: ""
            ) ?: return null
            val clientCertificate = normalizePem(
                prefs.getString(
                    Settings.PREF_VIBEVOICE_CLIENT_CERTIFICATE,
                    Defaults.PREF_VIBEVOICE_CLIENT_CERTIFICATE
                ) ?: ""
            )
            val clientPrivateKey = normalizePem(
                prefs.getString(
                    Settings.PREF_VIBEVOICE_CLIENT_PRIVATE_KEY,
                    Defaults.PREF_VIBEVOICE_CLIENT_PRIVATE_KEY
                ) ?: ""
            )
            if (!isCertificatePem(clientCertificate)) return null
            if (!isPrivateKeyPem(clientPrivateKey)) return null
            return ClientConfig(
                serverUrl = serverUrl,
                serverSpkiPin = serverSpkiPin,
                clientCertificatePem = clientCertificate,
                clientPrivateKeyPem = clientPrivateKey
            )
        }

        private fun parseCertificate(pem: String): X509Certificate {
            val factory = CertificateFactory.getInstance("X.509")
            return ByteArrayInputStream(normalizePem(pem).toByteArray(Charsets.US_ASCII)).use {
                factory.generateCertificate(it) as X509Certificate
            }
        }

        private fun parseEcPkcs8PrivateKey(pem: String): PrivateKey {
            val body = pemBody(pem, "PRIVATE KEY")
            val der = Base64.decode(body, Base64.DEFAULT)
            return try {
                KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
            } catch (e: GeneralSecurityException) {
                throw GeneralSecurityException("Client private key must be an unencrypted PKCS#8 EC key", e)
            }
        }

        private fun pemBody(pem: String, type: String): String {
            val normalized = normalizePem(pem)
            val begin = "-----BEGIN $type-----"
            val end = "-----END $type-----"
            val start = normalized.indexOf(begin)
            val finish = normalized.indexOf(end)
            if (start < 0 || finish <= start) {
                throw GeneralSecurityException("Missing PEM block: $type")
            }
            return normalized.substring(start + begin.length, finish)
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("")
        }

        internal fun serverSpkiPin(certificate: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
            return SERVER_PIN_PREFIX + Base64.encodeToString(digest, Base64.NO_WRAP)
        }

        internal fun peerMatchesServerPin(
            chain: Array<out X509Certificate>?,
            expectedPin: String
        ): Boolean {
            val normalizedExpectedPin = normalizeServerSpkiPin(expectedPin) ?: return false
            val leaf = chain?.firstOrNull() ?: return false
            return runCatching {
                serverSpkiPin(leaf) == normalizedExpectedPin
            }.getOrDefault(false)
        }

        private fun legacyPreferenceStores(context: Context): List<SharedPreferences> {
            val prefName = "${context.packageName}_preferences"
            val stores = mutableListOf(context.getSharedPreferences(prefName, Context.MODE_PRIVATE))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
                    ?.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    ?.let(stores::add)
            }
            return stores
        }
    }

    private data class ClientConfig(
        val serverUrl: String,
        val serverSpkiPin: String,
        val clientCertificatePem: String,
        val clientPrivateKeyPem: String
    )

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

    private val transcribeUrl = "${config.serverUrl}/v1/transcribe"
    private val healthUrl = "${config.serverUrl}/health"
    private val sslSocketFactory: SSLSocketFactory = createPinnedMtlsSocketFactory(config)
    private val hostnameVerifier: HostnameVerifier = ServerPinHostnameVerifier(config.serverSpkiPin)

    /** Active connection, if any. Volatile for cross-thread visibility. */
    @Volatile
    private var activeConnection: HttpsURLConnection? = null

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
            connection = openConnection(URL(transcribeUrl), "POST", doOutput = true).apply {
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
                setChunkedStreamingMode(0)
                readTimeout = 600_000
            }
            activeConnection = connection

            writeMultipartBody(connection.outputStream, audioFile)

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.e(TAG, "Transcription request failed with HTTP $responseCode")
                return null
            }

            return readSseStream(connection.inputStream, onPartialText)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            return null
        } finally {
            activeConnection = null
            connection?.disconnect()
        }
    }

    /**
     * Abort the current transcription request, if any. Safe to call from any thread.
     * The blocked [transcribe] call will throw an IOException and return null.
     */
    fun abort() {
        try {
            activeConnection?.disconnect()
        } catch (_: Exception) {
            // Force-closing the active request is best-effort.
        }
    }

    /** Authenticated proxy-local health check. */
    fun isHealthy(): Boolean {
        var connection: HttpsURLConnection? = null
        return try {
            connection = openConnection(URL(healthUrl), "GET", doOutput = false)
            connection.responseCode == 200
        } catch (e: Exception) {
            Log.w(TAG, "Health check failed", e)
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun openConnection(url: URL, method: String, doOutput: Boolean): HttpsURLConnection {
        return (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = this@VibeVoiceClient.sslSocketFactory
            hostnameVerifier = this@VibeVoiceClient.hostnameVerifier
            requestMethod = method
            this.doOutput = doOutput
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Connection", "close")
        }
    }

    private fun writeMultipartBody(output: OutputStream, audioFile: File) {
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("--$BOUNDARY\r\n")
            writer.write(
                "Content-Disposition: form-data; name=\"audio\"; filename=\"${safeMultipartFilename(audioFile.name)}\"\r\n"
            )
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

    private fun safeMultipartFilename(filename: String): String =
        filename.replace('\\', '_')
            .replace('"', '_')
            .replace('\r', '_')
            .replace('\n', '_')

    private fun readSseStream(
        input: InputStream,
        onPartialText: ((String) -> Unit)?
    ): TranscriptionResult? {
        val accumulated = StringBuilder()
        val dataLines = mutableListOf<String>()
        var eventName = "message"
        var eventChars = 0

        fun dispatchEvent(): Boolean {
            if (dataLines.isEmpty()) {
                eventName = "message"
                eventChars = 0
                return false
            }
            val payload = dataLines.joinToString("\n")
            dataLines.clear()
            eventChars = 0

            when (eventName) {
                "queue" -> Unit
                "done" -> return true
                "error" -> throw IOException(extractJsonField(payload, "error") ?: "Transcription failed")
                else -> {
                    val textValue = extractJsonField(payload, "text") ?: return false
                    if (accumulated.length + textValue.length > MAX_TRANSCRIPT_CHARS) {
                        throw IOException("Transcription response exceeded client limit")
                    }
                    accumulated.append(textValue)
                    onPartialText?.invoke(accumulated.toString())
                }
            }
            eventName = "message"
            return false
        }

        input.use {
            val lineBuffer = ByteArrayOutputStream()
            while (true) {
                val line = readUtf8Line(input, lineBuffer) ?: break
                when {
                    line.isEmpty() -> if (dispatchEvent()) break
                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val dataLine = line.removePrefix("data:").trimStart()
                        eventChars += dataLine.length + 1
                        if (eventChars > MAX_SSE_EVENT_CHARS) {
                            throw IOException("SSE event exceeded client limit")
                        }
                        dataLines.add(dataLine)
                    }
                    line.startsWith(":") -> Unit
                    else -> throw IOException("Invalid SSE line")
                }
            }
            dispatchEvent()
        }

        return parseTranscriptionJson(accumulated.toString())
    }

    private fun readUtf8Line(input: InputStream, buffer: ByteArrayOutputStream): String? {
        buffer.reset()
        while (true) {
            val next = input.read()
            if (next == -1) {
                if (buffer.size() == 0) return null
                break
            }
            if (next == '\n'.code) break
            if (buffer.size() >= MAX_SSE_LINE_BYTES) {
                throw IOException("SSE line exceeded client limit")
            }
            buffer.write(next)
        }

        var bytes = buffer.toByteArray()
        if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
            bytes = bytes.copyOf(bytes.size - 1)
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun extractJsonField(payload: String, field: String): String? {
        return try {
            val obj = json.decodeFromString<JsonObject>(payload)
            obj[field]?.jsonPrimitive?.content
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse SSE JSON event", e)
            null
        }
    }

    /**
     * Parse the accumulated JSON array of model segments:
     * [{"Start":0,"End":2.5,"Content":"Hello world"},...]
     */
    private fun parseTranscriptionJson(raw: String): TranscriptionResult? {
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
            Log.e(TAG, "Server returned an invalid transcription payload", e)
            null
        }
    }

    private fun createPinnedMtlsSocketFactory(config: ClientConfig): SSLSocketFactory {
        val clientCertificate = parseCertificate(config.clientCertificatePem)
        val clientPrivateKey = parseEcPkcs8PrivateKey(config.clientPrivateKeyPem)
        validateClientCertificate(clientCertificate)
        validateClientKeyMatchesCertificate(clientPrivateKey, clientCertificate)

        val trustManagers = arrayOf<TrustManager>(ServerPinTrustManager(config.serverSpkiPin))

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setKeyEntry("vvv-client", clientPrivateKey, CharArray(0), arrayOf(clientCertificate))
        }
        val keyManagers = KeyManagerFactory
            .getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, CharArray(0)) }
            .keyManagers

        val context = SSLContext.getInstance("TLS").apply {
            init(keyManagers, trustManagers, SecureRandom())
        }
        return Tls13OnlySocketFactory(context.socketFactory)
    }

    private class ServerPinTrustManager(
        private val expectedPin: String
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("VVV client mode does not accept peer client certificates")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (!peerMatchesServerPin(chain, expectedPin)) {
                throw CertificateException("VVV server public key pin mismatch")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class ServerPinHostnameVerifier(
        private val expectedPin: String
    ) : HostnameVerifier {
        override fun verify(hostname: String?, session: SSLSession?): Boolean {
            if (session == null) return false
            val chain = try {
                session.peerCertificates.filterIsInstance<X509Certificate>().toTypedArray()
            } catch (_: SSLPeerUnverifiedException) {
                return false
            }
            return peerMatchesServerPin(chain, expectedPin)
        }
    }

    private fun validateClientCertificate(certificate: X509Certificate) {
        certificate.checkValidity()
        if (certificate.basicConstraints >= 0) {
            throw GeneralSecurityException("Client certificate must not be a CA certificate")
        }
        val keyUsage = certificate.keyUsage
        if (keyUsage != null && (keyUsage.isEmpty() || !keyUsage[0])) {
            throw GeneralSecurityException("Client certificate must allow digital signatures")
        }
        val extendedKeyUsage = certificate.extendedKeyUsage
        if (extendedKeyUsage != null && CLIENT_AUTH_EKU !in extendedKeyUsage) {
            throw GeneralSecurityException("Client certificate must include clientAuth EKU")
        }
    }

    private fun validateClientKeyMatchesCertificate(
        privateKey: PrivateKey,
        certificate: X509Certificate
    ) {
        val probe = "vvv-client-key-check".toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(probe)
        val signedProbe = signature.sign()

        signature.initVerify(certificate.publicKey)
        signature.update(probe)
        if (!signature.verify(signedProbe)) {
            throw GeneralSecurityException("Client private key does not match client certificate")
        }
    }

    private class Tls13OnlySocketFactory(
        private val delegate: SSLSocketFactory
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(socket: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            delegate.createSocket(socket, host, port, autoClose).requireTls13()

        override fun createSocket(host: String?, port: Int): Socket =
            delegate.createSocket(host, port).requireTls13()

        override fun createSocket(
            host: String?,
            port: Int,
            localHost: InetAddress?,
            localPort: Int
        ): Socket = delegate.createSocket(host, port, localHost, localPort).requireTls13()

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            delegate.createSocket(host, port).requireTls13()

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int
        ): Socket = delegate.createSocket(address, port, localAddress, localPort).requireTls13()

        private fun Socket.requireTls13(): Socket {
            if (this !is SSLSocket) return this
            if (TLS_PROTOCOL !in supportedProtocols) {
                throw SSLException("VVV requires TLS 1.3")
            }
            enabledProtocols = arrayOf(TLS_PROTOCOL)
            return this
        }
    }
}

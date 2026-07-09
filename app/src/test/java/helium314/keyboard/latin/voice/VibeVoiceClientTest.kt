// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.protectedPrefs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class VibeVoiceClientTest {
    @Test fun `server URL must be an HTTPS origin`() {
        assertEquals(
            "https://vvv.example.invalid:42862",
            VibeVoiceClient.normalizeServerUrl("  https://vvv.example.invalid:42862/  ")
        )

        assertEquals(null, VibeVoiceClient.normalizeServerUrl(""))
        assertEquals(null, VibeVoiceClient.normalizeServerUrl("http://vvv.example.invalid:42862"))
        assertEquals(null, VibeVoiceClient.normalizeServerUrl("https://user@vvv.example.invalid:42862"))
        assertEquals(null, VibeVoiceClient.normalizeServerUrl("https://vvv.example.invalid:42862/v1/transcribe"))
        assertEquals(null, VibeVoiceClient.normalizeServerUrl("https://vvv.example.invalid:42862?x=1"))
        assertEquals(null, VibeVoiceClient.normalizeServerUrl("https://vvv.example.invalid:42862/#fragment"))
        assertEquals(null, VibeVoiceClient.normalizeServerUrl("https://[2001:db8::10]:42862"))
    }

    @Test fun `PEM validation rejects wrong material types`() {
        assertTrue(VibeVoiceClient.isCertificatePem(TEST_CERTIFICATE))
        assertFalse(VibeVoiceClient.isCertificatePem(TEST_EC_PRIVATE_KEY))
        assertFalse(VibeVoiceClient.isCertificatePem("-----BEGIN CERTIFICATE-----\nnot-base64\n-----END CERTIFICATE-----"))

        assertTrue(VibeVoiceClient.isPrivateKeyPem(TEST_EC_PRIVATE_KEY))
        assertFalse(VibeVoiceClient.isPrivateKeyPem(TEST_CERTIFICATE))
        assertFalse(VibeVoiceClient.isPrivateKeyPem("-----BEGIN EC PRIVATE KEY-----\nnot-pkcs8\n-----END EC PRIVATE KEY-----"))
    }

    @Test fun `server SPKI pin is canonical SHA-256 base64`() {
        val pin = VibeVoiceClient.serverSpkiPinForCertificatePem(TEST_CERTIFICATE)

        assertTrue(pin != null && VibeVoiceClient.isServerSpkiPin(pin))
        assertEquals(pin, VibeVoiceClient.normalizeServerSpkiPin("  $pin\n"))
        assertFalse(VibeVoiceClient.isServerSpkiPin(""))
        assertFalse(VibeVoiceClient.isServerSpkiPin("sha256/not-base64"))
        assertFalse(VibeVoiceClient.isServerSpkiPin("sha1/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
        assertFalse(VibeVoiceClient.isServerSpkiPin("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
    }

    @Test fun `server identity is exact peer public key pin`() {
        val pin = VibeVoiceClient.serverSpkiPinForCertificatePem(TEST_CERTIFICATE)
        val cert = VibeVoiceClient.parseCertificateForTest(TEST_CERTIFICATE)

        assertTrue(VibeVoiceClient.peerMatchesServerPin(arrayOf(cert), pin ?: ""))
        assertFalse(VibeVoiceClient.peerMatchesServerPin(arrayOf(cert), WRONG_SERVER_PIN))
        assertFalse(VibeVoiceClient.peerMatchesServerPin(emptyArray(), pin ?: ""))
    }

    @Test fun `PEM normalization trims and uses LF line endings`() {
        assertEquals(
            "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n",
            VibeVoiceClient.normalizePem("\r\n-----BEGIN PRIVATE KEY-----\r\nabc\r\n-----END PRIVATE KEY-----\r\n")
        )
    }

    @Test fun `client bundle import writes current hardened preferences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.protectedPrefs()
        prefs.edit().clear().commit()
        val bundle = clientBundleJson(serverUrl = " https://vvv.example.invalid:42862/ ")

        assertTrue(VibeVoiceClient.isClientImportBundle(bundle))
        assertTrue(VibeVoiceClient.importClientBundle(context, bundle))

        assertEquals(
            "https://vvv.example.invalid:42862",
            prefs.getString(Settings.PREF_VIBEVOICE_SERVER_URL, "")
        )
        assertEquals(
            TEST_SERVER_PIN,
            prefs.getString(Settings.PREF_VIBEVOICE_SERVER_SPKI_PIN, "")
        )
        assertEquals(
            VibeVoiceClient.normalizePem(TEST_CLIENT_CERTIFICATE),
            prefs.getString(Settings.PREF_VIBEVOICE_CLIENT_CERTIFICATE, "")
        )
        assertEquals(
            VibeVoiceClient.normalizePem(TEST_CLIENT_PRIVATE_KEY),
            prefs.getString(Settings.PREF_VIBEVOICE_CLIENT_PRIVATE_KEY, "")
        )
        assertTrue(VibeVoiceClient.isConfigured(context))
    }

    @Test fun `client bundle parser is strict`() {
        assertFalse(VibeVoiceClient.isClientImportBundle(clientBundleJson(type = "wrong")))
        assertFalse(VibeVoiceClient.isClientImportBundle(clientBundleJson(version = 2)))
        assertFalse(VibeVoiceClient.isClientImportBundle(clientBundleJson(serverUrl = "http://vvv.example.invalid")))
        assertFalse(VibeVoiceClient.isClientImportBundle(clientBundleJson(serverPin = "sha256/not-base64")))
        assertFalse(VibeVoiceClient.isClientImportBundle(clientBundleJson(clientKey = TEST_EC_PRIVATE_KEY)))
        assertFalse(VibeVoiceClient.isClientImportBundle(
            clientBundleJson(clientCertificate = TEST_CERTIFICATE, clientKey = TEST_EC_PRIVATE_KEY)
        ))
        assertFalse(VibeVoiceClient.isClientImportBundle(
            clientBundleJson().dropLast(1) + ",\"unexpected\":true}"
        ))
    }

    private companion object {
        private const val TEST_SERVER_PIN =
            "sha256/ERERERERERERERERERERERERERERERERERERERERERE="

        private const val TEST_CERTIFICATE = """
-----BEGIN CERTIFICATE-----
MIIBrTCCAVOgAwIBAgIUMgcIeKd0z5rGtEw5msd690Zjwm8wCgYIKoZIzj0EAwIw
GzEZMBcGA1UEAwwQdnZ2LXRlc3QuaW52YWxpZDAeFw0yNjA3MDkxNzAxMjJaFw0z
NjA3MDYxNzAxMjJaMBsxGTAXBgNVBAMMEHZ2di10ZXN0LmludmFsaWQwWTATBgcq
hkjOPQIBBggqhkjOPQMBBwNCAAQ0lcL2IOlfDRG9SWsiTBjhGnAO15EIXGWKsVzs
s6GQZBM1FapetgRotclZSBK4z+HaP8w53DlG9Y04xZMrEkd5o3UwczAdBgNVHQ4E
FgQUe29NV8NFx7tiG9nniNmrmX1TOHcwHwYDVR0jBBgwFoAUe29NV8NFx7tiG9nn
iNmrmX1TOHcwDwYDVR0TAQH/BAUwAwEB/zALBgNVHQ8EBAMCB4AwEwYDVR0lBAww
CgYIKwYBBQUHAwIwCgYIKoZIzj0EAwIDSAAwRQIgU7TbbgjM5Fy5AC70pDk6dmzP
F3FbttpHo2SzP0yAP5gCIQDNMfWIWrGNN8sjJmQDgiG0eNOmICjp6LXF1o/Gi0r+
cg==
-----END CERTIFICATE-----
"""

        private const val TEST_EC_PRIVATE_KEY = """
-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgaNZzMw05UPemgkDV
YRw7SYYizUlzFSFPf5/ouXBzUsuhRANCAAQ0lcL2IOlfDRG9SWsiTBjhGnAO15EI
XGWKsVzss6GQZBM1FapetgRotclZSBK4z+HaP8w53DlG9Y04xZMrEkd5
-----END PRIVATE KEY-----
"""

        private const val WRONG_SERVER_PIN =
            "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        private const val TEST_CLIENT_CERTIFICATE = """
-----BEGIN CERTIFICATE-----
MIIBbzCCARSgAwIBAgIUSuxsMlTqnpa8n5U6VltoT06les8wCgYIKoZIzj0EAwIw
HTEbMBkGA1UEAwwSVlZWIENsaWVudCBBdXRoIENBMB4XDTI2MDcwOTIxMjczOVoX
DTI3MDcwOTIxMjczOVowFzEVMBMGA1UEAwwMYW5kcm9pZC10ZXN0MFkwEwYHKoZI
zj0CAQYIKoZIzj0DAQcDQgAEOCM9PhgRTaip4Lul1/5mQ03bG9RYBzHMEqa4BnzD
9t7C7odhX4ZnHBR6r3myJmq/WlYNGTCdgrbJMbLqhKuNHKM4MDYwDAYDVR0TAQH/
BAIwADAOBgNVHQ8BAf8EBAMCB4AwFgYDVR0lAQH/BAwwCgYIKwYBBQUHAwIwCgYI
KoZIzj0EAwIDSQAwRgIhAKXiD++5cYjT1g8fSzKTuKBha8HQcNOa7B0QEuoOPMqZ
AiEAgN1R9ivaK4E/10Axnciv0iWg8Cwh4Go4MMgehfB0iAw=
-----END CERTIFICATE-----
"""

        private const val TEST_CLIENT_PRIVATE_KEY = """
-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg81RBJaNHsYyx0y1J
G1f+VATbDc/R4WWg+Kl/wJKDv9mhRANCAAQ4Iz0+GBFNqKngu6XX/mZDTdsb1FgH
McwSprgGfMP23sLuh2FfhmccFHqvebImar9aVg0ZMJ2CtskxsuqEq40c
-----END PRIVATE KEY-----
"""

        private fun clientBundleJson(
            type: String = "vvv-client-config",
            version: Int = 1,
            serverUrl: String = "https://vvv.example.invalid:42862",
            serverPin: String = TEST_SERVER_PIN,
            clientCertificate: String = TEST_CLIENT_CERTIFICATE,
            clientKey: String = TEST_CLIENT_PRIVATE_KEY
        ): String = buildJsonObject {
            put("type", type)
            put("version", version)
            put("server_url", serverUrl)
            put("server_spki_pin", serverPin)
            put("client_certificate_pem", VibeVoiceClient.normalizePem(clientCertificate))
            put("client_private_key_pem", VibeVoiceClient.normalizePem(clientKey))
        }.toString()
    }
}

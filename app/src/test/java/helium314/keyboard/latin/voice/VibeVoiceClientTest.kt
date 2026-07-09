// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Settings
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

    @Test fun `hardened config keys do not reuse pre-hardened preference names`() {
        val legacyKeys = setOf(
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

        assertFalse(Settings.PREF_VIBEVOICE_SERVER_URL in legacyKeys)
        assertFalse(Settings.PREF_VIBEVOICE_SERVER_SPKI_PIN in legacyKeys)
        assertFalse(Settings.PREF_VIBEVOICE_CLIENT_CERTIFICATE in legacyKeys)
        assertFalse(Settings.PREF_VIBEVOICE_CLIENT_PRIVATE_KEY in legacyKeys)
    }

    @Test fun `legacy VibeVoice preferences are purged from credential and device stores`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefName = "${context.packageName}_preferences"
        val legacyKeys = listOf(
            "vibevoice_server_url",
            "vibevoice_auth_token",
            "vvv_public_api_v2_server_url",
            "vvv_public_api_v2_auth_token",
        )

        val credentialPrefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        credentialPrefs.edit()
            .putString("vibevoice_server_url", "https://legacy.example.invalid")
            .putString("vibevoice_auth_token", "aaa.bbb.ccc")
            .putString("vvv_public_api_v2_server_url", "https://v2.example.invalid")
            .putString("vvv_public_api_v2_auth_token", "aaa.bbb.ccc")
            .commit()

        val devicePrefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(prefName, Context.MODE_PRIVATE)
        } else {
            null
        }
        devicePrefs?.edit()
            ?.putString("vibevoice_server_url", "https://legacy.example.invalid")
            ?.putString("vibevoice_auth_token", "aaa.bbb.ccc")
            ?.putString("vvv_public_api_v2_server_url", "https://v2.example.invalid")
            ?.putString("vvv_public_api_v2_auth_token", "aaa.bbb.ccc")
            ?.commit()

        VibeVoiceClient.forgetLegacyConfiguration(context)

        legacyKeys.forEach {
            assertFalse(credentialPrefs.contains(it))
            if (devicePrefs != null) assertFalse(devicePrefs.contains(it))
        }
    }

    private companion object {
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
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

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
        assertEquals(null, VibeVoiceClient.normalizeServerUrl("https://vvv.example.invalid:42862?token=abc.def.ghi"))
        assertEquals(null, VibeVoiceClient.normalizeServerUrl("https://vvv.example.invalid:42862/#fragment"))
    }

    @Test fun `auth token is a raw bearer JWT string`() {
        assertTrue(VibeVoiceClient.isBearerToken("aaa.bbb.ccc"))
        assertTrue(VibeVoiceClient.isBearerToken("aaa.bbb.ccc\n"))

        assertFalse(VibeVoiceClient.isBearerToken(""))
        assertFalse(VibeVoiceClient.isBearerToken("Bearer aaa.bbb.ccc"))
        assertFalse(VibeVoiceClient.isBearerToken("aaa.bbb"))
        assertFalse(VibeVoiceClient.isBearerToken("aaa.bbb.ccc ddd"))
        assertFalse(VibeVoiceClient.isBearerToken("aaa.bbb.\nccc"))
    }

    @Test fun `PEM validation rejects wrong material types`() {
        assertTrue(VibeVoiceClient.isCertificatePem(TEST_CERTIFICATE))
        assertFalse(VibeVoiceClient.isCertificatePem(TEST_EC_PRIVATE_KEY))
        assertFalse(VibeVoiceClient.isCertificatePem("-----BEGIN CERTIFICATE-----\nnot-base64\n-----END CERTIFICATE-----"))

        assertTrue(VibeVoiceClient.isPrivateKeyPem(TEST_EC_PRIVATE_KEY))
        assertFalse(VibeVoiceClient.isPrivateKeyPem(TEST_CERTIFICATE))
        assertFalse(VibeVoiceClient.isPrivateKeyPem("-----BEGIN EC PRIVATE KEY-----\nnot-pkcs8\n-----END EC PRIVATE KEY-----"))
    }

    @Test fun `PEM normalization trims and uses LF line endings`() {
        assertEquals(
            "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n",
            VibeVoiceClient.normalizePem("\r\n-----BEGIN PRIVATE KEY-----\r\nabc\r\n-----END PRIVATE KEY-----\r\n")
        )
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
    }
}

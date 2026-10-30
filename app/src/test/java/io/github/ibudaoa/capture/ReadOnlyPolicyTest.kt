package io.github.ibudaoa.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyPolicyTest {
    @Test fun `only exact audited reads are allowed`() {
        listOf("Run2/beforeRunV260", "User/User", "Run/GetAllZone", "Run/GetZonePoints").forEach {
            assertTrue(ReadOnlyPolicy.allows("POST", "/v3/api.php/$it"))
        }
    }

    @Test fun `writes and misleading get endpoints cannot be forwarded`() {
        listOf("Run/setRunZone", "Run/setRunLocationRecord", "Run/stopRunV278", "Run/getTimestampV278",
            "My/getSignupPoints", "Run2/faceVerify", "Login/RefreshToken", "report/getOssSts").forEach {
            assertFalse(ReadOnlyPolicy.allows("POST", "/v3/api.php/$it"))
        }
    }

    @Test fun `path ambiguity method overrides and alternative hosts fail closed`() {
        listOf("${ReadOnlyPolicy.BEFORE_RUN}?s=/Run/start", "${ReadOnlyPolicy.BEFORE_RUN}/",
            "/v3/api.php/Run2/../Run/stopRunV278", "/v3/api.php/Run2/%62eforeRunV260",
            "https://${ReadOnlyPolicy.HOST}${ReadOnlyPolicy.BEFORE_RUN}").forEach {
            assertFalse(ReadOnlyPolicy.allows("POST", it))
        }
        assertFalse(ReadOnlyPolicy.allows("GET", ReadOnlyPolicy.BEFORE_RUN))
        assertTrue(ReadOnlyPolicy.allowsConnect("api2.lptiyu.com:443"))
        listOf("api2.lptiyu.com.evil.invalid:443", "api2.lptiyu.com:80", "api.lptiyu.com:443").forEach {
            assertFalse(ReadOnlyPolicy.allowsConnect(it))
        }
    }

    @Test fun `blocked categories discard raw targets including credentials in query strings`() {
        assertTrue(ReadOnlyPolicy.blockedKind("/v3/api.php/My/getSignupPoints?token=SYNTHETIC_SECRET") == BlockedRequestKind.SIGNUP)
        assertTrue(ReadOnlyPolicy.blockedKind("https://private.invalid/login?token=SYNTHETIC_SECRET") == BlockedRequestKind.OTHER)
    }
}

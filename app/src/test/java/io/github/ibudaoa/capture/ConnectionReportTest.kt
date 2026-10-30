package io.github.ibudaoa.capture

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionReportTest {
    @Test fun `failure report contains enum evidence but never private message or response`() {
        val state = CaptureState(phase = CapturePhase.ERROR, message = "PRIVATE_MESSAGE_SENTINEL",
            problem = CaptureProblem.TLS, blockedRequests = 2, blockedKinds = setOf(BlockedRequestKind.SIGNUP),
            config = DiagnosticConfig(Json.parseToJsonElement("""{"token":"PRIVATE_RESPONSE_SENTINEL"}""").jsonObject))
        val report = ConnectionReport.encode(state, certificateInstalled = false, configCaptured = false)
        assertTrue(report.contains("TLS"))
        assertTrue(report.contains("SIGNUP"))
        assertFalse(report.contains("PRIVATE_"))
        assertFalse(report.contains("token"))
    }
}

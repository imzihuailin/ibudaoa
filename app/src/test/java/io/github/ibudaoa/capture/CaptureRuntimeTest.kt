package io.github.ibudaoa.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureRuntimeTest {
    @Test fun `late callbacks cannot revive cancelled capture or overwrite a newer generation`() {
        val old = CaptureRuntime.begin()
        CaptureRuntime.update(old) { it.copy(phase = CapturePhase.CANCELLED) }
        CaptureRuntime.update(old) { it.copy(phase = CapturePhase.CAPTURED) }
        assertEquals(CapturePhase.CANCELLED, CaptureRuntime.state.value.phase)
        val current = CaptureRuntime.begin()
        CaptureRuntime.update(old) { it.copy(phase = CapturePhase.ERROR) }
        assertEquals(current, CaptureRuntime.state.value.generation)
        assertEquals(CapturePhase.STARTING, CaptureRuntime.state.value.phase)
        CaptureRuntime.update(current) { it.copy(phase = CapturePhase.CAPTURED) }
        CaptureRuntime.update(current) { it.copy(phase = CapturePhase.CANCELLED) }
        assertEquals(CapturePhase.CAPTURED, CaptureRuntime.state.value.phase)
    }

    @Test fun `profile belongs only to the current capture generation`() {
        val old = CaptureRuntime.begin()
        CaptureRuntime.update(old) { it.copy(profile = AccountProfilePreview("甲", "学校")) }
        val current = CaptureRuntime.begin()
        assertEquals(null, CaptureRuntime.state.value.profile)
        CaptureRuntime.update(old) { it.copy(profile = AccountProfilePreview("旧用户", "学校")) }
        assertEquals(current, CaptureRuntime.state.value.generation)
        assertEquals(null, CaptureRuntime.state.value.profile)
    }
    @Test fun `account read outcome belongs to a single explicitly selected purpose and generation`() {
        val old=CaptureRuntime.begin(CapturePurpose.ACCOUNT_READ_CHECK)
        CaptureRuntime.update(old) { it.copy(accountRead=AccountReadResult(AccountReadOutcome.PASSED,1,true,true),phase=CapturePhase.CAPTURED) }
        assertEquals(CapturePurpose.ACCOUNT_READ_CHECK,CaptureRuntime.state.value.purpose)
        CaptureRuntime.begin()
        assertEquals(CapturePurpose.CONFIG,CaptureRuntime.state.value.purpose)
        assertEquals(null,CaptureRuntime.state.value.accountRead)
        CaptureRuntime.update(old) { it.copy(accountRead=AccountReadResult(AccountReadOutcome.PASSED,1,true,true)) }
        assertEquals(null,CaptureRuntime.state.value.accountRead)
    }

}

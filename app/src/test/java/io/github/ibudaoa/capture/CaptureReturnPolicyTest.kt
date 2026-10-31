package io.github.ibudaoa.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureReturnPolicyTest {
    @Test fun `return from official releases only current active collection`() {
        for(purpose in CapturePurpose.entries)for(phase in listOf(CapturePhase.STARTING,CapturePhase.LISTENING)) {
            assertTrue("$purpose $phase",CaptureReturnPolicy.shouldRelease(CaptureState(purpose=purpose,phase=phase,officialOpened=true)))
        }
    }
    @Test fun `old terminal state cannot stop a newly requested foreground service`() {
        for (phase in listOf(CapturePhase.CANCELLED, CapturePhase.ERROR, CapturePhase.CAPTURED, CapturePhase.IDLE)) {
            assertFalse("$phase", CaptureReturnPolicy.shouldRelease(CaptureState(phase=phase,officialOpened=true)))
        }
    }
    @Test fun `permission dialog or first launch does not cancel collection before opening official`() {
        for(purpose in CapturePurpose.entries)for(phase in CapturePhase.entries) {
            assertFalse("$purpose $phase",CaptureReturnPolicy.shouldRelease(CaptureState(purpose=purpose,phase=phase,officialOpened=false)))
        }
        assertFalse(CaptureReturnPolicy.shouldRelease(CaptureState(phase=CapturePhase.IDLE,officialOpened=true)))
    }
}

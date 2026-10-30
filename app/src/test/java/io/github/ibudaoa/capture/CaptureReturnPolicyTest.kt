package io.github.ibudaoa.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureReturnPolicyTest {
    @Test fun `return from official releases incomplete successful failed and cancelled collection`() {
        for(purpose in CapturePurpose.entries)for(phase in CapturePhase.entries.filter { it!=CapturePhase.IDLE }) {
            assertTrue("$purpose $phase",CaptureReturnPolicy.shouldRelease(CaptureState(purpose=purpose,phase=phase,officialOpened=true)))
        }
    }
    @Test fun `permission dialog or first launch does not cancel collection before opening official`() {
        for(purpose in CapturePurpose.entries)for(phase in CapturePhase.entries) {
            assertFalse("$purpose $phase",CaptureReturnPolicy.shouldRelease(CaptureState(purpose=purpose,phase=phase,officialOpened=false)))
        }
        assertFalse(CaptureReturnPolicy.shouldRelease(CaptureState(phase=CapturePhase.IDLE,officialOpened=true)))
    }
}

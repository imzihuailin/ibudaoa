package io.github.ibudaoa.running

import org.junit.Assert.*
import org.junit.Test

class RunVerificationWindowTest {
    @Test fun `manual confirmation opens at sixty foreground seconds only`() {
        val window=RunVerificationWindow()
        window.resume(1000)
        assertEquals(AutomaticVerificationState.VERIFYING,window.state(1000))
        assertEquals(AutomaticVerificationState.VERIFYING,window.state(60_999))
        assertEquals(AutomaticVerificationState.MANUAL,window.state(61_000))
        assertEquals(0L,window.remaining(90_000))
    }
    @Test fun `background time does not consume or reset the budget`() {
        val window=RunVerificationWindow()
        window.resume(1000);window.pause(21_000)
        assertEquals(40_000L,window.remaining(300_000))
        window.resume(300_000)
        assertEquals(AutomaticVerificationState.VERIFYING,window.state(339_999))
        assertEquals(AutomaticVerificationState.MANUAL,window.state(340_000))
    }
    @Test fun `repeated foreground events never extend the deadline`() {
        val window=RunVerificationWindow()
        window.resume(1000);window.resume(31_000)
        assertEquals(0L,window.remaining(61_000))
        window.pause(61_000);window.pause(90_000);window.resume(100_000)
        assertEquals(AutomaticVerificationState.MANUAL,window.state(100_000))
    }
    @Test fun `an in flight finish never exposes manual buttons even without a record number`() {
        for(phase in AutomaticRunPhase.entries) {
            val pending=AutomaticRunSummary(phase,pendingWrite="FINISH")
            assertFalse(pending.manualConfirmationAvailable)
            assertFalse(pending.copy(verification=AutomaticVerificationState.VERIFYING).manualConfirmationAvailable)
            assertEquals(phase in setOf(AutomaticRunPhase.AWAITING_VERDICT,AutomaticRunPhase.INTERRUPTED),
                pending.copy(verification=AutomaticVerificationState.MANUAL).manualConfirmationAvailable)
        }
    }
    @Test fun `process reconstruction gets a fresh window`() {
        val old=RunVerificationWindow();old.resume(0)
        assertEquals(AutomaticVerificationState.MANUAL,old.state(60_000))
        val restored=RunVerificationWindow();restored.resume(900_000)
        assertEquals(60_000L,restored.remaining(900_000))
    }
}

package io.github.ibudaoa.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {
    @Test fun `backgrounding stops running locally and keeps record and points`() {
        val session = SessionState(SessionPhase.RUNNING, "record-fixture", listOf("point-a", "point-b"))
        val interrupted = session.onForegroundLost()
        assertEquals(SessionPhase.INTERRUPTED, interrupted.phase)
        assertEquals(session.recordId, interrupted.recordId)
        assertEquals(session.selectedPointIds, interrupted.selectedPointIds)
        assertTrue(interrupted.needsReconciliation)
        assertFalse(interrupted.keepScreenOn)
    }

    @Test fun `returning never automatically resumes or pretends platform pause`() {
        val interrupted = SessionState(SessionPhase.RUNNING).onForegroundLost()
        assertEquals(interrupted, interrupted.onForegroundReturned())
        assertEquals(SessionPhase.INTERRUPTED, interrupted.phase)
    }

    @Test fun `active task including pause and submission keeps screen on`() {
        SessionPhase.entries.forEach { phase ->
            assertEquals(phase in setOf(SessionPhase.PREPARING,SessionPhase.RUNNING,SessionPhase.PAUSED,SessionPhase.AWAITING_VERDICT), SessionState(phase).keepScreenOn)
        }
    }

    @Test fun `leaving during preparation or pause requires reconciliation`() {
        listOf(SessionPhase.PREPARING, SessionPhase.PAUSED).forEach { phase ->
            val result = SessionState(phase).onForegroundLost()
            assertEquals(SessionPhase.INTERRUPTED, result.phase)
            assertTrue(result.needsReconciliation)
        }
    }

    @Test fun `idle and verdict queries do not become fabricated interrupted runs`() {
        listOf(SessionPhase.IDLE, SessionPhase.AWAITING_VERDICT, SessionPhase.VALID,
            SessionPhase.INVALID, SessionPhase.QUERY_FAILED).forEach { phase ->
            assertEquals(SessionState(phase), SessionState(phase).onForegroundLost())
        }
    }
}

package io.github.ibudaoa.maps

import io.github.ibudaoa.domain.CampusCoordinate
import org.junit.Assert.*
import org.junit.Test

class ForegroundFixGateTest {
    private val coordinate = CampusCoordinate(30.46, 114.43)
    private fun sample(gate: ForegroundFixGate, attempt: Long, accuracy: Float = 10f, gcj: Boolean = true,
        mock: Boolean = false, sdkTime: Long = 100_000L, elapsed: Long = 2_000L,
        at: CampusCoordinate = coordinate) = gate.accept(attempt, at, accuracy, gcj, mock, sdkTime, 100_000, elapsed)

    @Test fun `stop clears position and rejects queued callbacks`() {
        val gate = ForegroundFixGate()
        val first = gate.begin()
        assertNotNull(sample(gate, first))
        gate.stop()
        assertNull(gate.current(2_001))
        assertNull(sample(gate, first))
    }

    @Test fun `resume waits for a new fix and rejects a previous session callback`() {
        val gate = ForegroundFixGate()
        val first = gate.begin()
        sample(gate, first)
        gate.stop()
        val second = gate.begin()
        assertNull(gate.current(2_000))
        assertNull(sample(gate, first))
        assertNotNull(sample(gate, second))
    }

    @Test fun `wrong frame mock inaccurate and invalid coordinates clear an existing fix`() {
        val gate = ForegroundFixGate()
        val id = gate.begin()
        val invalid: List<() -> DisplayFix?> = listOf(
            { sample(gate, id, gcj = false) }, { sample(gate, id, mock = true) },
            { sample(gate, id, accuracy = 50.01f) }, { sample(gate, id, accuracy = Float.NaN) },
            { sample(gate, id, accuracy = 0f) }, { sample(gate, id, at = CampusCoordinate(91.0, 114.0)) },
            { sample(gate, id, at = CampusCoordinate(30.0, Double.NaN)) },
            { sample(gate, id, sdkTime = 0) }, { sample(gate, id, sdkTime = 100_001) },
            { sample(gate, id, sdkTime = 84_999) }, { sample(gate, id, elapsed = -1) },
        )
        invalid.forEach {
            assertNotNull(sample(gate, id))
            assertNull(it())
            assertNull(gate.current(2_000))
        }
    }

    @Test fun `freshness uses elapsed clock with exact expiry boundary`() {
        val gate = ForegroundFixGate()
        val id = gate.begin()
        assertNotNull(sample(gate, id, accuracy = 50f, sdkTime = 85_000))
        assertNotNull(gate.current(17_000))
        assertNull(gate.current(17_001))
        assertNull(gate.current(1_999))
    }

    @Test fun `transient errors clear fix without breaking later callbacks`() {
        val gate = ForegroundFixGate()
        val id = gate.begin()
        assertNotNull(sample(gate, id))
        gate.invalidate()
        assertNull(gate.current(2_000))
        assertNotNull(sample(gate, id))
    }

    @Test fun `late old callback cannot clear fresh position in new session`() {
        val gate = ForegroundFixGate()
        val old = gate.begin()
        val current = gate.begin()
        val fix = sample(gate, current)
        assertNull(sample(gate, old, mock = true))
        assertEquals(fix, gate.current(2_000))
    }
    @Test fun `precise fresh sample in the wrong frame does not get mislabeled as inaccurate`() {
        val gate=ForegroundFixGate();val id=gate.begin()
        assertNull(sample(gate,id,gcj=false,accuracy=5f))
        assertEquals(FixRejection.WRONG_FRAME,gate.lastRejection)
        assertNull(sample(gate,id,sdkTime=100_001))
        assertEquals(FixRejection.FUTURE_TIME,gate.lastRejection)
        assertNull(sample(gate,id,sdkTime=84_999))
        assertEquals(FixRejection.STALE_TIME,gate.lastRejection)
        assertNotNull(sample(gate,id))
        assertNull(gate.lastRejection)
    }
    @Test fun `expiry is emitted once only after an accepted location becomes stale`() {
        val gate=ForegroundFixGate();val id=gate.begin()
        assertFalse(gate.expireIfNeeded(2000))
        assertNotNull(sample(gate,id))
        assertFalse(gate.expireIfNeeded(17_000))
        assertTrue(gate.expireIfNeeded(17_001))
        assertEquals(FixRejection.EXPIRED,gate.lastRejection)
        assertFalse(gate.expireIfNeeded(17_002))
        assertNull(sample(gate,id,accuracy=100f))
        assertFalse(gate.expireIfNeeded(18_000))
        assertEquals(FixRejection.ACCURACY,gate.lastRejection)
    }

}

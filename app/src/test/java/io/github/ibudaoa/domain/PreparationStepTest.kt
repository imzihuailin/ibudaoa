package io.github.ibudaoa.domain

import io.github.ibudaoa.roads.RoadBatchPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class PreparationStepTest {
    private fun next(captured: Boolean = true, revision: Int = 1, active: Boolean = false,
        key: Boolean = true, consent: Boolean = true, phase: RoadBatchPhase? = null, busy: Boolean = false) =
        PreparationStepResolver.next(captured, revision, 1, active, key, consent, phase, busy)

    @Test fun `legacy missing or unknown capture revision requests one fresh collection`() {
        assertEquals(PreparationStep.COLLECT_CONFIG, next(revision = 0))
        assertEquals(PreparationStep.COLLECT_CONFIG, next(revision = 2))
        assertEquals(PreparationStep.COLLECT_CONFIG, next(captured = false))
        assertEquals(PreparationStep.CHECK_ROADS, next(revision = 1))
    }
    @Test fun `listening returns to official app even with an old already captured snapshot`() {
        assertEquals(PreparationStep.RETURN_OFFICIAL, next(revision = 0, active = true))
        assertEquals(PreparationStep.RETURN_OFFICIAL, next(active = true))
    }
    @Test fun `fresh capture requires key and actual consent before road checking`() {
        assertEquals(PreparationStep.MAP_UNAVAILABLE, next(key = false))
        assertEquals(PreparationStep.ENABLE_LOCATION, next(consent = false))
        assertEquals(PreparationStep.CHECK_ROADS, next())
    }
    @Test fun `running never duplicates requests and interrupted only retries on user continue`() {
        assertEquals(PreparationStep.WAIT, next(phase = RoadBatchPhase.RUNNING))
        assertEquals(PreparationStep.WAIT, next(busy = true))
        assertEquals(PreparationStep.RETRY, next(phase = RoadBatchPhase.INTERRUPTED))
    }
    @Test fun `old road review repeats roads without repeating the fresh configuration capture`() {
        assertEquals(PreparationStep.CHECK_ROADS, PreparationStepResolver.next(true, 1, 1, false,
            true, true, RoadBatchPhase.COMPLETE, false, 1))
        assertEquals(PreparationStep.CHECK_ROADS, PreparationStepResolver.next(true, 1, 1, false,
            true, true, RoadBatchPhase.COMPLETE, false, 2))
    }

    @Test fun `incomplete or failed check requires explicit retry instead of export or automatic loop`() {
        assertEquals(PreparationStep.RETRY, next(phase = RoadBatchPhase.COMPLETE))
        assertEquals(PreparationStep.RETRY, next(phase = RoadBatchPhase.FAILED))
    }
    @Test fun `only available saved roads allow completion after prerequisites`() {
        assertEquals(PreparationStep.READY, PreparationStepResolver.next(true, 1, 1, false,
            true, true, RoadBatchPhase.COMPLETE, false, roadsReady = true))
        assertEquals(PreparationStep.WAIT, PreparationStepResolver.next(true, 1, 1, false,
            true, true, RoadBatchPhase.COMPLETE, true, roadsReady = true))
        assertEquals(PreparationStep.ENABLE_LOCATION, PreparationStepResolver.next(true, 1, 1, false,
            true, false, RoadBatchPhase.COMPLETE, false, roadsReady = true))
    }

    @Test fun `missing precise location blocks even when roads are already saved`() {
        assertEquals(PreparationStep.ENABLE_LOCATION, PreparationStepResolver.next(true, 1, 1, false,
            true, true, RoadBatchPhase.COMPLETE, false, roadsReady = true, locationGranted = false))
    }

    @Test fun `restoring location reuses saved roads while first setup still checks roads`() {
        assertEquals(PreparationStep.READY, PreparationStepResolver.next(true, 1, 1, false,
            true, true, RoadBatchPhase.COMPLETE, false, roadsReady = true, locationGranted = true))
        assertEquals(PreparationStep.CHECK_ROADS, PreparationStepResolver.next(true, 1, 1, false,
            true, true, null, false, locationGranted = true))
        assertEquals(PreparationStep.COLLECT_CONFIG, PreparationStepResolver.next(false, 1, 1, false,
            true, false, null, false, locationGranted = false))
    }

}

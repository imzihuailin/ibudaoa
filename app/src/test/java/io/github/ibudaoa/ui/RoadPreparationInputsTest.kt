package io.github.ibudaoa.ui

import io.github.ibudaoa.AppState
import io.github.ibudaoa.roads.RoadBatchPhase
import io.github.ibudaoa.roads.RoadBatchPlan
import io.github.ibudaoa.roads.RoadBatchReport
import io.github.ibudaoa.roads.RoadFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RoadPreparationInputsTest {
    @Test fun `unrelated state updates reuse inputs but road scope and report changes invalidate them`() {
        val config = RoadFixture.config()
        val plan = RoadBatchPlan.create(config, 1)
        val report = RoadBatchReport(plan.scope, RoadBatchPhase.RUNNING, plan.candidateCount, plan.requests.size)
        val state = AppState(config = config, connectionEpoch = 1, roadReport = report, capturedConfig = true, captureRevision = 1)
        val inputs = RoadPreparationInputs.from(state)

        assertEquals(inputs, RoadPreparationInputs.from(state.copy(locationGranted = true, busy = true)))
        assertNotEquals(inputs, RoadPreparationInputs.from(state.copy(connectionEpoch = 2)))
        assertNotEquals(inputs, RoadPreparationInputs.from(state.copy(roadReport = report.copy(phase = RoadBatchPhase.COMPLETE))))
        assertNotEquals(inputs, RoadPreparationInputs.from(state.copy(capturedConfig = false)))
        assertNotEquals(inputs, RoadPreparationInputs.from(state.copy(config = null)))
    }
}

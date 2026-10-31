package io.github.ibudaoa.domain

import io.github.ibudaoa.roads.*
import org.junit.Assert.*
import org.junit.Test

class PreparationRoadsTest {
    private val config = RoadFixture.config()
    private val plan = RoadBatchPlan.create(config, 1)
    private val report = RoadBatchReport(plan.scope, RoadBatchPhase.COMPLETE, plan.candidateCount,
        plan.requests.size, plan.requests.map { RoadLinkSummary(it.fromId, it.toId) })
    private val evidence = RoadEvidenceSnapshot(plan.scope, plan.requests.map {
        CandidateRoadEvidence(it.fromId, it.toId, WalkingLeg(100.0, listOf(listOf(it.from, it.to))))
    })

    @Test fun `saved complete roads can be reused without querying again`() {
        assertTrue(PreparationRoads.ready(config, 1, report, evidence))
    }
    @Test fun `old account epoch school zone and review cannot enable completion`() {
        assertFalse(PreparationRoads.ready(config, 2, report, evidence))
        assertFalse(PreparationRoads.ready(RoadFixture.config(zone=4), 1, report, evidence))
        assertFalse(PreparationRoads.ready(config, 1, report.copy(reviewRevision=1), evidence))
        assertFalse(PreparationRoads.ready(null, 1, report, evidence))
    }
    @Test fun `summary alone or partial geometry never counts as ready`() {
        assertFalse(PreparationRoads.ready(config, 1, report, null))
        assertFalse(PreparationRoads.ready(config, 1, report, evidence.copy(legs=evidence.legs.take(1))))
        assertFalse(PreparationRoads.ready(config, 1, report.copy(links=report.links.take(1)), evidence))
        assertFalse(PreparationRoads.ready(config, 1, report, evidence.copy(scope=plan.scope.copy(connectionEpoch=2))))
    }
    @Test fun `failure interruption and pending state cannot enable completion`() {
        for (phase in RoadBatchPhase.entries.filter { it != RoadBatchPhase.COMPLETE })
            assertFalse(PreparationRoads.ready(config, 1, report.copy(phase=phase), evidence))
        assertFalse(PreparationRoads.ready(config, 1,
            report.copy(links=report.links.map { it.copy(failure=RoadLinkFailure.NO_ROUTE) }), evidence))
    }
}

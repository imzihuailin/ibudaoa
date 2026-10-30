package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.PreparationExport
import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.maps.AmapDistance
import org.junit.Assert.*
import org.junit.Test

class RoadGapCheckTest {
    private val config = RoadFixture.config()
    private val scope = RoadBatchPlan.create(config, 1).scope
    private val before = CampusCoordinate(30.001, 114.0)
    private val after = CampusCoordinate(30.001006, 114.0)
    private val leg = WalkingLeg(222.39, listOf(listOf(RoadFixture.a, before), listOf(after, RoadFixture.c)))
    private val evidence = RoadEvidenceSnapshot(scope, listOf(CandidateRoadEvidence("a", "b", leg)))

    @Test fun `plan derives gap only from validated current campus evidence`() {
        val plan = RoadGapPlan.create(config, evidence, AmapDistance())
        assertEquals(1, plan.eligibleGapCount); assertEquals(listOf(RoadGap(before, after)), plan.gaps)
        assertTrue(runCatching { RoadGapPlan.create(RoadFixture.config(zone = 4), evidence, AmapDistance()) }.isFailure)
        assertTrue(runCatching { RoadGapPlan.create(RoadFixture.config(forbidden = "[\"114,30\"]"), evidence, AmapDistance()) }.isFailure)
    }

    @Test fun `only continuous returned geometry anchored at both endpoints resolves a gap`() {
        val gap = RoadGap(before, after)
        assertEquals(GapCheckOutcome.EXACT_CONNECTED, RoadGapReviewer.review(gap, listOf(WalkingLeg(1.0, listOf(listOf(before, after))))))
        assertEquals(GapCheckOutcome.OFFSET_OR_GAP, RoadGapReviewer.review(gap, listOf(WalkingLeg(1.0,
            listOf(listOf(before, CampusCoordinate(30.001001, 114.0)), listOf(CampusCoordinate(30.001002, 114.0), after))))))
        assertEquals(GapCheckOutcome.OFFSET_OR_GAP, RoadGapReviewer.review(gap, listOf(WalkingLeg(1.0,
            listOf(listOf(before, CampusCoordinate(30.001005, 114.0)))))))
        assertEquals(GapCheckOutcome.INVALID_GEOMETRY, RoadGapReviewer.review(gap, listOf(WalkingLeg(0.0, listOf(listOf(before, after))))))
        assertEquals(GapCheckOutcome.NO_ROUTE, RoadGapReviewer.review(gap, emptyList()))
    }

    @Test fun `large gap list is bounded without creating extra requests`() {
        val steps = (0 until 31).map { i -> listOf(CampusCoordinate(30.0 + i * 0.00006, 114.0),
            CampusCoordinate(30.0 + (i + 1) * 0.00006 - 0.000005, 114.0)) }
        val snapshot = RoadEvidenceSnapshot(scope, listOf(CandidateRoadEvidence("a", "b", WalkingLeg(190.0, steps))))
        val plan = RoadGapPlan.create(config, snapshot, AmapDistance())
        assertEquals(30, plan.eligibleGapCount)
        assertEquals(RoadGapPlan.MAX_REQUESTS, plan.gaps.size)
    }

    @Test fun `export includes only scoped counts and cannot leak gap coordinates`() {
        val plan = RoadGapPlan.create(config, evidence, AmapDistance())
        val graph = ObservedRoadGraphBuilder.build(config, evidence, AmapDistance())
        val report = RoadBatchReport(scope, RoadBatchPhase.INTERRUPTED, 3, 2, listOf(RoadLinkSummary("a", "b")))
        val gaps = RoadGapReport(scope, plan.evidenceFingerprint, 1, 1, listOf(GapCheckOutcome.OFFSET_OR_GAP), false)
        val export = PreparationExport.encode(config, 1, 1, report, graph = graph, gapReport = gaps)
        assertTrue(export.contains("\"offset_or_gap\": 1")); assertFalse(export.contains("30.001006"))
        assertFalse(PreparationExport.encode(config, 1, 1, report, graph = graph,
            gapReport = gaps.copy(evidenceFingerprint = "0".repeat(64))).contains("road_gap_check"))
    }
}

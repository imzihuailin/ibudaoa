package io.github.ibudaoa.roads

import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.maps.AmapDistance
import org.junit.Assert.*
import org.junit.Test

class RoadGapEvidenceTest {
    private val config = RoadFixture.config()
    private val scope = RoadBatchPlan.create(config, 1).scope
    private val before = CampusCoordinate(30.001, 114.0)
    private val after = CampusCoordinate(30.001006, 114.0)
    private val gap = RoadGap(before, after)
    private val road = WalkingLeg(222.39, listOf(listOf(RoadFixture.a, before), listOf(after, RoadFixture.c)))
    private val evidence = RoadEvidenceSnapshot(scope, listOf(CandidateRoadEvidence("a", "b", road)))
    private val bridge = WalkingLeg(0.67, listOf(listOf(before, after)))
    private val result = RoadGapEvidence(scope, RoadGapPlan.fingerprint(evidence), listOf(RoadGapRecord(gap, GapCheckOutcome.EXACT_CONNECTED, bridge)))

    @Test fun `private gap evidence repairs a directed local draft without inventing geometry`() {
        assertEquals(result, RoadGapEvidenceCodec.decode(RoadGapEvidenceCodec.encode(result)))
        val base = ObservedRoadGraphBuilder.build(config, evidence, AmapDistance())
        val updated = ObservedRoadGraphBuilder.build(config, evidence, AmapDistance(), result)
        assertEquals(2, base.topology!!.weakComponents)
        assertEquals(1, updated.topology!!.weakComponents)
        assertEquals(1, updated.verifiedGapBridges)
        assertEquals(1, updated.edges.size)
        assertEquals(listOf(RoadFixture.a, before, after, RoadFixture.c), updated.edges.single().points)
        assertEquals(1, updated.locallyResolvedGaps)
        assertTrue(updated.unjoinedLegs.isEmpty())
        val foreign = result.copy(evidenceFingerprint = "0".repeat(64))
        val rejected = ObservedRoadGraphBuilder.build(config, evidence, AmapDistance(), foreign)
        assertEquals(0, rejected.verifiedGapBridges)
        assertTrue(rejected.edges.isEmpty())
    }

    @Test fun `invalid incomplete and unrelated geometries are never saved as exact bridges`() {
        assertTrue(runCatching { RoadGapEvidenceCodec.encode(result.copy(records = listOf(result.records[0].copy(bridge = null)))) }.isFailure)
        assertTrue(runCatching { RoadGapEvidenceCodec.encode(result.copy(records = listOf(result.records[0].copy(
            bridge = WalkingLeg(1.0, listOf(listOf(before, RoadFixture.c))))))) }.isFailure)
        assertTrue(runCatching { RoadGapEvidenceCodec.decode("[".repeat(100) + "]".repeat(100)) }.isFailure)
        val wrongGap = RoadGap(RoadFixture.a, before)
        val unrelated = result.copy(records = listOf(RoadGapRecord(wrongGap, GapCheckOutcome.EXACT_CONNECTED,
            WalkingLeg(1.0, listOf(listOf(RoadFixture.a, before))))))
        assertEquals(0, ObservedRoadGraphBuilder.build(config, evidence, AmapDistance(), unrelated).verifiedGapBridges)
    }
    @Test fun `only an explicit scoped manual confirmation joins this gap and is not SDK evidence`() {
        val plan = RoadGapPlan.create(config, evidence, AmapDistance())
        val manual = ManualRoadGaps(plan.evidenceFingerprint, setOf(0))
        assertEquals(manual, ManualRoadGapsCodec.decode(ManualRoadGapsCodec.encode(manual)))
        val graph = ObservedRoadGraphBuilder.build(config, evidence, AmapDistance(), manualGaps = manual)
        assertEquals(1, graph.edges.size)
        assertEquals(1, graph.manualConfirmationCount)
        assertEquals(0, graph.verifiedGapBridges)
        assertEquals(listOf(RoadFixture.a, before, after, RoadFixture.c), graph.edges.single().points)
        val stale = ObservedRoadGraphBuilder.build(config, evidence, AmapDistance(), manualGaps = manual.copy(evidenceFingerprint = "0".repeat(64)))
        assertTrue(stale.edges.isEmpty())
        assertEquals(0, stale.manualConfirmationCount)
        val wrong = ObservedRoadGraphBuilder.build(config, evidence, AmapDistance(), manualGaps = manual.copy(indices = setOf(1)))
        assertTrue(wrong.edges.isEmpty())
    }

}

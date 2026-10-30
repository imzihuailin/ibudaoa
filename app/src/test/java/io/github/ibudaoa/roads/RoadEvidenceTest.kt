package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.DiagnosticCodec
import io.github.ibudaoa.diagnostics.PreparationExport
import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.maps.AmapDistance
import org.junit.Assert.*
import org.junit.Test

class RoadEvidenceTest {
    private val config = RoadFixture.config()
    private val plan = RoadBatchPlan.create(config, 1)
    private val road = CandidateRoadEvidence("a", "b", RoadFixture.leg())
    private val evidence = RoadEvidenceSnapshot(plan.scope, listOf(road))

    @Test fun `private evidence roundtrips but does not enter the user diagnostic export`() {
        val text = RoadEvidenceCodec.encode(evidence)
        assertEquals(evidence, RoadEvidenceCodec.decode(text))
        val report = RoadBatchReport(plan.scope, RoadBatchPhase.INTERRUPTED, 3, 2,
            listOf(RoadLinkSummary("a", "b")))
        val export = PreparationExport.encode(config, 1, 1, report)
        assertFalse(export.contains("30.001")) // intermediate road coordinate, absent from official candidate config
        assertEquals(config.config, DiagnosticCodec.decode(export).config)
    }

    @Test fun `unknown schema oversized and malformed geometry cannot load`() {
        val text = RoadEvidenceCodec.encode(evidence)
        assertTrue(runCatching { RoadEvidenceCodec.decode(text.replace("\"schema\":1", "\"schema\":2")) }.isFailure)
        assertTrue(runCatching { RoadEvidenceCodec.decode("{" + "[".repeat(100) + "]".repeat(100) + "}") }.isFailure)
        assertTrue(runCatching { RoadEvidenceCodec.encode(evidence.copy(legs = listOf(road.copy(
            leg = WalkingLeg(1.0, listOf(List(RoadEvidenceCodec.MAX_POINTS + 1) { RoadFixture.a })))))) }.isFailure)
        assertTrue(runCatching { RoadEvidenceCodec.encode(evidence.copy(legs = listOf(road.copy(
            leg = WalkingLeg(1.0, listOf(listOf(RoadFixture.a, CampusCoordinate(Double.NaN, 114.0)))))))) }.isFailure)
    }

    @Test fun `only observed connected forward edges enter the graph`() {
        val graph = ObservedRoadGraphBuilder.build(config, evidence, AmapDistance())
        assertEquals(1, graph.edges.size)
        assertEquals("a", graph.edges.single().fromId); assertEquals("b", graph.edges.single().toId)
        assertEquals(0, graph.edges.count { it.fromId == "b" && it.toId == "a" })
        assertTrue(graph.unjoinedLegs.isEmpty())
    }

    @Test fun `export includes only scoped aggregate graph counts`() {
        val graph = ObservedRoadGraphBuilder.build(config, evidence, AmapDistance())
        val report = RoadBatchReport(plan.scope, RoadBatchPhase.INTERRUPTED, 3, 2, listOf(RoadLinkSummary("a", "b")))
        val export = PreparationExport.encode(config, 1, 1, report, graph = graph)
        assertTrue(export.contains("\"continuous_directed_links\": 1"))
        assertFalse(export.contains("30.001"))
        assertFalse(PreparationExport.encode(config, 2, 1, report, graph = graph).contains("road_graph_summary"))
    }

    @Test fun `unobserved small gap remains unavailable to the route graph`() {
        val gap = CandidateRoadEvidence("a", "b", WalkingLeg(222.39, listOf(
            listOf(RoadFixture.a, RoadFixture.b),
            listOf(CampusCoordinate(30.001006, 114.0), RoadFixture.c))))
        val graph = ObservedRoadGraphBuilder.build(config, evidence.copy(legs = listOf(gap)), AmapDistance())
        assertTrue(graph.edges.isEmpty()); assertEquals(setOf("a" to "b"), graph.unjoinedLegs)
    }

    @Test fun `road snapped away from the checkpoint is not given an invented connector`() {
        val offset = CandidateRoadEvidence("a", "b", WalkingLeg(111.2, listOf(listOf(RoadFixture.b, RoadFixture.c))))
        val graph = ObservedRoadGraphBuilder.build(config, evidence.copy(legs = listOf(offset)), AmapDistance())
        assertTrue(graph.edges.isEmpty()); assertEquals(setOf("a" to "b"), graph.offsetLegs)
    }

    @Test fun `disconnected and offset are reported independently for the same road`() {
        val gapAndOffset = CandidateRoadEvidence("a", "b", WalkingLeg(110.0, listOf(
            listOf(CampusCoordinate(30.0002, 114.0), CampusCoordinate(30.0008, 114.0)),
            listOf(CampusCoordinate(30.000806, 114.0), CampusCoordinate(30.0012, 114.0)))))
        val graph = ObservedRoadGraphBuilder.build(config, evidence.copy(legs = listOf(gapAndOffset)), AmapDistance())
        assertTrue(graph.edges.isEmpty())
        assertEquals(setOf("a" to "b"), graph.unjoinedLegs)
        assertEquals(setOf("a" to "b"), graph.offsetLegs)
    }

    @Test fun `client proximity does not turn a disconnected road into a route`() {
        val supported = DiagnosticCodec.decode(config.config.toString().replace("\"run_line_info\":{",
            "\"role\":1,\"authStatus\":2,\"point_type\":1,\"set_point_type\":1,\"repeat_num\":0," +
                "\"log_mode\":1,\"is_location_white_list\":0,\"run_line_info\":{\"log_max_distance\":100,"))
        val scopedPlan = RoadBatchPlan.create(supported, 1)
        val gapAndOffset = CandidateRoadEvidence("a", "b", WalkingLeg(110.0, listOf(
            listOf(CampusCoordinate(30.0002, 114.0), CampusCoordinate(30.0008, 114.0)),
            listOf(CampusCoordinate(30.000806, 114.0), CampusCoordinate(30.0012, 114.0)))))
        val graph = ObservedRoadGraphBuilder.build(supported, RoadEvidenceSnapshot(scopedPlan.scope, listOf(gapAndOffset)), AmapDistance())
        assertEquals(CheckpointEndpointProximity(true, true), graph.endpointProximity?.get("a" to "b"))
        assertTrue(graph.edges.isEmpty())
        assertTrue(("a" to "b") in graph.unjoinedLegs)
        val report = RoadBatchReport(scopedPlan.scope, RoadBatchPhase.INTERRUPTED, 3, 2, listOf(RoadLinkSummary("a", "b")))
        val export = PreparationExport.encode(supported, 1, 1, report, graph = graph)
        assertTrue(export.contains("\"checkpoint_proximity_both_endpoints_links\": 1"))
        assertFalse(export.contains("30.000806"))
        val narrow = DiagnosticCodec.decode(supported.config.toString().replace("\"log_max_distance\":100", "\"log_max_distance\":10"))
        val narrowPlan = RoadBatchPlan.create(narrow, 1)
        val narrowGraph = ObservedRoadGraphBuilder.build(narrow, RoadEvidenceSnapshot(narrowPlan.scope, listOf(gapAndOffset)), AmapDistance())
        assertEquals(CheckpointEndpointProximity(false, false), narrowGraph.endpointProximity?.get("a" to "b"))
        assertNull(ObservedRoadGraphBuilder.build(config, evidence, AmapDistance()).endpointProximity)
    }

    @Test fun `old or foreign configuration cannot be used to construct a graph`() {
        assertTrue(runCatching { ObservedRoadGraphBuilder.build(RoadFixture.config(zone = 4), evidence, AmapDistance()) }.isFailure)
        assertTrue(runCatching { ObservedRoadGraphBuilder.build(config,
            evidence.copy(scope = evidence.scope.copy(connectionEpoch = 2)), AmapDistance()) }.isSuccess)
        // Changing scope requires the storage layer to reject stale epochs; the graph itself uses the supplied epoch.
    }
}

package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.PreparationExport
import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.maps.AmapDistance
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RoadGapShapeExportTest {
    private val config = RoadFixture.config()
    private val plan = RoadBatchPlan.create(config, 1)
    private val before = CampusCoordinate(30.001, 114.0)
    private val after = CampusCoordinate(30.001006, 114.0)
    private val leg = WalkingLeg(222.39, listOf(listOf(RoadFixture.a, before), listOf(after, RoadFixture.c)))
    private val evidence = RoadEvidenceSnapshot(plan.scope, listOf(CandidateRoadEvidence("a", "b", leg)))
    private val metric = AmapDistance()

    @Test fun `shape retains decimal delta and direction without absolute coordinates`() {
        val shape = RoadGapShapeExport.build(config, evidence, metric)
        val sample = shape.getValue("samples").jsonArray.single().jsonObject
        assertEquals(0.000006, sample.getValue("after_relative").jsonArray.first().jsonArray[0].jsonPrimitive.double, 0.0)
        assertEquals(-0.001, sample.getValue("before_relative").jsonArray.first().jsonArray[0].jsonPrimitive.double, 0.0)
        assertEquals(6, sample.getValue("after_decimal_places").jsonArray.first().jsonArray[0].jsonPrimitive.int)
        for (absolute in listOf("30.001", "30.002", "114.0")) assertFalse(shape.toString().contains(absolute))
        assertEquals(leg, evidence.legs.single().leg)
    }

    @Test fun `only validated same scope evidence is included in user export`() {
        val graph = ObservedRoadGraphBuilder.build(config, evidence, metric)
        val report = RoadBatchReport(plan.scope, RoadBatchPhase.INTERRUPTED, 3, 2, listOf(RoadLinkSummary("a", "b")))
        fun export(e: RoadEvidenceSnapshot) = PreparationExport.encode(config, 1, 1, report,
            graph = graph, evidence = e, distance = metric)
        assertTrue(export(evidence).contains("road_gap_shapes"))
        assertFalse(export(evidence.copy(scope = plan.scope.copy(connectionEpoch = 2))).contains("road_gap_shapes"))
        val replaced = evidence.copy(legs = listOf(evidence.legs.single().copy(leg = leg.copy(distanceMeters = 222.4))))
        assertFalse(export(replaced).contains("road_gap_shapes"))
    }

    @Test fun `invalid geometry and foreign configuration cannot expose a sample`() {
        val invalid = evidence.copy(legs = listOf(evidence.legs.single().copy(leg = leg.copy(distanceMeters = 1.0))))
        assertTrue(RoadGapShapeExport.build(config, invalid, metric).getValue("samples").jsonArray.isEmpty())
        assertTrue(runCatching { RoadGapShapeExport.build(RoadFixture.config(zone = 4), evidence, metric) }.isFailure)
    }
}

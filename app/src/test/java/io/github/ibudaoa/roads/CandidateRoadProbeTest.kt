package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.DiagnosticCodec
import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.domain.PlatformDistance
import io.github.ibudaoa.maps.AmapDistance
import org.junit.Assert.*
import org.junit.Test

internal object RoadFixture {
    val a = CampusCoordinate(30.0, 114.0)
    val b = CampusCoordinate(30.001, 114.0)
    val c = CampusCoordinate(30.002, 114.0)
    fun config(zone: Int = 3, boundary: String = "[\"113.999,29.999\",\"114.001,29.999\",\"114.001,30.003\",\"113.999,30.003\"]", forbidden: String = "[]") =
        DiagnosticCodec.decode("""{"school_id":1,"term_id":2,"run_zone_id":$zone,"run_line_info":{"point_list":[
            {"id":"a","jingwei":"30.0,114.0","is_online":1,"type":2,"point_type":1},
            {"id":"b","jingwei":"30.002,114.0","is_online":1,"type":2,"point_type":1},
            {"id":"outside","jingwei":"30.004,114.0","is_online":1,"type":2,"point_type":1}]},
            "run_zone_latlng":$boundary,"run_zone_latlng_invalid":$forbidden}""")
    fun request(zone: Int = 3, epoch: Long = 1) = CandidateRoadRequest.create(config(zone), epoch, "a", "b")
    fun leg() = WalkingLeg(222.39, listOf(listOf(a, b), listOf(b, c)))
}

class CandidateRoadProbeTest {
    private val distance = AmapDistance()
    private fun inspect(leg: WalkingLeg = RoadFixture.leg(), config: io.github.ibudaoa.diagnostics.DiagnosticConfig = RoadFixture.config(),
        expected: CandidateRoadRequest = RoadFixture.request(), actual: CandidateRoadRequest = expected,
        source: String = WalkingRoadReviewer.SOURCE, metric: PlatformDistance = distance): WalkingLegReview =
        WalkingRoadReviewer.review(expected, WalkingReply(actual, source, listOf(leg)), leg, config, metric)

    @Test fun `coherent connected road retains actual geometry and reports snap offsets`() {
        val result = inspect()
        assertTrue(result.issues.isEmpty())
        assertEquals(222.39, result.geometryMeters!!, 1.0)
        assertEquals(0.0, result.startOffsetMeters!!, 0.0)
        assertEquals(0.0, result.endOffsetMeters!!, 0.0)
        assertEquals(false, result.leavesConfiguredBoundary)
        assertFalse(result.forbiddenRegionUnknown)
    }

    @Test fun `config scope epoch endpoint direction and source changes cannot be accepted as current`() {
        assertTrue(RoadGeometryIssue.STALE_CONTEXT in inspect(actual = RoadFixture.request(zone = 4)).issues)
        assertTrue(RoadGeometryIssue.STALE_CONTEXT in inspect(actual = RoadFixture.request(epoch = 2)).issues)
        assertTrue(RoadGeometryIssue.STALE_CONTEXT in inspect(config = RoadFixture.config(zone = 4)).issues)
        val reverse = CandidateRoadRequest.create(RoadFixture.config(), 1, "b", "a")
        assertTrue(RoadGeometryIssue.MISMATCHED_QUERY in inspect(actual = reverse).issues)
        assertTrue(RoadGeometryIssue.INVALID_SOURCE in inspect(source = "synthetic").issues)
    }

    @Test fun `stale or wrong-source geometry cannot contribute measurements or region findings`() {
        listOf(inspect(actual = RoadFixture.request(zone = 4)), inspect(source = "synthetic")).forEach {
            assertNull(it.geometryMeters)
            assertNull(it.startOffsetMeters)
            assertNull(it.endOffsetMeters)
            assertNull(it.leavesConfiguredBoundary)
        }
    }

    @Test fun `gaps and missing geometry are diagnosed without a straight connector`() {
        val gap = WalkingLeg(222.39, listOf(listOf(RoadFixture.a, RoadFixture.b), listOf(RoadFixture.c, RoadFixture.b)))
        val result = inspect(gap)
        assertTrue(RoadGeometryIssue.DISCONNECTED_STEPS in result.issues)
        assertTrue(result.maximumStepGapMeters!! > 100)
        assertEquals(222.39, result.geometryMeters!!, 1.0)
        assertNull(result.leavesConfiguredBoundary)
        assertTrue(RoadGeometryIssue.MISSING_GEOMETRY in inspect(WalkingLeg(100.0, listOf(emptyList()))).issues)
    }

    @Test fun `metre scale step gaps preserve measured roads without silently closing the gap`() {
        val before = CampusCoordinate(30.001, 114.0)
        val after = CampusCoordinate(30.001006, 114.0)
        val steps = listOf(listOf(RoadFixture.a, before), listOf(after, RoadFixture.c))
        val leg = WalkingLeg(222.39, steps)
        val result = inspect(leg)
        assertTrue(RoadGeometryIssue.DISCONNECTED_STEPS in result.issues)
        assertTrue(result.maximumStepGapMeters!! in 0.6..0.8)
        val knownLength = distance.meters(RoadFixture.a, before) + distance.meters(after, RoadFixture.c)
        assertEquals(knownLength, result.geometryMeters!!, 0.001)
        assertTrue(result.geometryMeters < distance.meters(RoadFixture.a, RoadFixture.c))
        assertNull(result.leavesConfiguredBoundary)
        assertEquals(steps, leg.steps)
    }

    @Test fun `observed exit remains reportable even when a step boundary is not aligned`() {
        val leg = WalkingLeg(444.78, listOf(listOf(RoadFixture.a, RoadFixture.b),
            listOf(CampusCoordinate(30.001006, 114.0), CampusCoordinate(30.004, 114.0))))
        assertEquals(true, inspect(leg).leavesConfiguredBoundary)
    }

    @Test fun `invalid coordinates bad metrics and inconsistent total cannot become road distance`() {
        val invalid = WalkingLeg(100.0, listOf(listOf(RoadFixture.a, CampusCoordinate(Double.NaN, 114.0))))
        assertTrue(RoadGeometryIssue.INVALID_COORDINATE in inspect(invalid).issues)
        assertTrue(RoadGeometryIssue.INVALID_DISTANCE in inspect(RoadFixture.leg().copy(distanceMeters = -1.0)).issues)
        assertTrue(RoadGeometryIssue.DISTANCE_INCONSISTENT in inspect(RoadFixture.leg().copy(distanceMeters = 10.0)).issues)
        val bad = object : PlatformDistance {
            override val implementationId = WalkingRoadReviewer.DISTANCE
            override fun meters(from: CampusCoordinate, to: CampusCoordinate) = Double.NaN
        }
        assertTrue(RoadGeometryIssue.DISTANCE_UNAVAILABLE in inspect(metric = bad).issues)
    }

    @Test fun `boundary findings never remove an outside official endpoint`() {
        val config = RoadFixture.config()
        val outside = CandidateRoadRequest.create(config, 1, "a", "outside")
        val leg = WalkingLeg(444.78, listOf(listOf(outside.from, outside.to)))
        assertEquals(true, inspect(leg, config, outside).leavesConfiguredBoundary)
        assertEquals("outside", outside.toId)
        val unknown = inspect(config = RoadFixture.config(boundary = "[]", forbidden = "[\"114,30\"]"))
        assertNull(unknown.leavesConfiguredBoundary)
        assertTrue(unknown.forbiddenRegionUnknown)
    }

    @Test fun `snapping offsets are measured and never joined to the road polyline`() {
        val leg = WalkingLeg(111.2, listOf(listOf(RoadFixture.b, RoadFixture.c)))
        val result = inspect(leg)
        assertTrue(result.startOffsetMeters!! > 100)
        assertEquals(2, leg.steps.single().size)
        assertEquals(RoadFixture.b, leg.steps.single().first())
    }

    @Test fun `unknown ids same ids or incomplete scope fail before route requests`() {
        assertTrue(runCatching { CandidateRoadRequest.create(RoadFixture.config(), 0, "a", "b") }.isFailure)
        assertTrue(runCatching { CandidateRoadRequest.create(RoadFixture.config(), 1, "a", "a") }.isFailure)
        assertTrue(runCatching { CandidateRoadRequest.create(RoadFixture.config(), 1, "a", "unknown") }.isFailure)
    }
}

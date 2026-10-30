package io.github.ibudaoa.maps

import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.RouteSearchV2
import com.amap.api.services.route.WalkPath
import com.amap.api.services.route.WalkRouteResultV2
import com.amap.api.services.route.WalkStep
import io.github.ibudaoa.roads.RoadFixture
import org.junit.Assert.*
import org.junit.Test

class AmapWalkingReplyMapperTest {
    private fun result(): WalkRouteResultV2 {
        val request = RoadFixture.request()
        return WalkRouteResultV2().apply {
            walkQuery = RouteSearchV2.WalkRouteQuery(RouteSearchV2.FromAndTo(
                LatLonPoint(request.from.latitude, request.from.longitude),
                LatLonPoint(request.to.latitude, request.to.longitude),
            ))
            paths = listOf(WalkPath().apply {
                distance = 222.39f
                steps = listOf(WalkStep().apply {
                    road = "untrusted-road-text"
                    instruction = "untrusted-instruction-text"
                    polyline = listOf(LatLonPoint(30.0, 114.0), LatLonPoint(30.002, 114.0))
                })
            })
        }
    }
    private fun failure(result: WalkRouteResultV2?, code: Int = 1000): RoadProbeFailure {
        val error = runCatching { AmapWalkingReplyMapper.map(RoadFixture.request(), result, code) }.exceptionOrNull()
        assertTrue(error is RoadProbeException)
        return (error as RoadProbeException).reason
    }

    @Test fun `maps typed SDK road coordinates and metre distance without SDK free text`() {
        val reply = AmapWalkingReplyMapper.map(RoadFixture.request(), result(), 1000)
        assertEquals(222.39, reply.paths.single().distanceMeters, 0.01)
        assertEquals(RoadFixture.a, reply.paths.single().steps.single().first())
        assertFalse(reply.toString().contains("untrusted-road-text"))
        assertFalse(reply.toString().contains("untrusted-instruction-text"))
    }

    @Test fun `error status and absent results remain failures even with plausible geometry`() {
        assertEquals(RoadProbeFailure.SDK_FAILURE, failure(result(), 10001))
        assertEquals(RoadProbeFailure.SDK_FAILURE, failure(null))
        assertEquals(RoadProbeFailure.EMPTY_RESULT, failure(result().apply { paths = emptyList() }))
    }

    @Test fun `callback query must match the requested endpoint coordinates`() {
        val changed = result().apply { walkQuery.fromAndTo.to.latitude = 31.0 }
        assertEquals(RoadProbeFailure.MISMATCHED_QUERY, failure(changed))
        assertEquals(RoadProbeFailure.MISMATCHED_QUERY, failure(result().apply { walkQuery = null }))
    }

    @Test fun `oversized route replies are bounded before copying coordinates`() {
        val large = result().apply { paths = List(4) { paths.single() } }
        assertEquals(RoadProbeFailure.TOO_COMPLEX, failure(large))
        val many = result().apply {
            paths.single().steps.single().polyline = List(20_001) { LatLonPoint(30.0, 114.0) }
        }
        assertEquals(RoadProbeFailure.TOO_COMPLEX, failure(many))
    }

    @Test fun `SDK status categories distinguish authorization quota and network without raw text`() {
        assertEquals(RoadProbeFailure.AUTHORIZATION, roadFailure(com.amap.api.services.core.AMapException.CODE_AMAP_INVALID_USER_SCODE))
        assertEquals(RoadProbeFailure.QUOTA, roadFailure(com.amap.api.services.core.AMapException.CODE_AMAP_ACCESS_TOO_FREQUENT))
        assertEquals(RoadProbeFailure.NETWORK, roadFailure(com.amap.api.services.core.AMapException.CODE_AMAP_CLIENT_NETWORK_EXCEPTION))
        assertEquals(RoadProbeFailure.SDK_FAILURE, roadFailure(-123456))
    }

    @Test fun `missing step geometry stays missing rather than using path-level straight lines`() {
        val response = result().apply {
            paths.single().steps = emptyList()
            paths.single().polyline = listOf(LatLonPoint(30.0, 114.0), LatLonPoint(30.002, 114.0))
        }
        val reply = AmapWalkingReplyMapper.map(RoadFixture.request(), response, 1000)
        assertTrue(reply.paths.single().steps.isEmpty())
    }

    @Test fun `SDK path level concatenation cannot hide a gap between returned steps`() {
        val first = LatLonPoint(30.0, 114.0); val before = LatLonPoint(30.001, 114.0)
        val after = LatLonPoint(30.001006, 114.0); val last = LatLonPoint(30.002, 114.0)
        val response = result().apply {
            paths.single().steps = listOf(WalkStep().apply { polyline = listOf(first, before) },
                WalkStep().apply { polyline = listOf(after, last) })
            paths.single().polyline = listOf(first, before, after, last)
        }
        val request = RoadFixture.request()
        val reply = AmapWalkingReplyMapper.map(request, response, 1000)
        val leg = reply.paths.single()
        val review = io.github.ibudaoa.roads.WalkingRoadReviewer.review(request, reply, leg, RoadFixture.config(), AmapDistance())
        assertTrue(io.github.ibudaoa.roads.RoadGeometryIssue.DISCONNECTED_STEPS in review.issues)
        assertFalse(io.github.ibudaoa.roads.ObservedRoadAssembly.assemble(leg.steps).continuous)
    }
}

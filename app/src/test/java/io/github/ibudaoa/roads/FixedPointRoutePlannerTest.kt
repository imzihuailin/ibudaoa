package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.DiagnosticCodec
import io.github.ibudaoa.domain.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FixedPointRoutePlannerTest {
    private val day=LocalDate.of(2026,10,28)
    private val time=30_000
    private val metric="amap-11.3.100-calculateLineDistance-v1"
    private val config=DiagnosticCodec.decode("""{"school_id":1,"term_id":2,"run_zone_id":3,
        "role":1,"authStatus":2,"set_point_type":1,"point_type":1,"repeat_num":0,"log_mode":1,"log_logic":1,"is_limit_range":0,
        "run_face_verify_config":{},"time_rule_arr":[{"start_time":21600,"end_time":79200,
        "min_distance":0.1,"max_distance":1,"max_pace":180,"min_pace":540,"min_log_num":2,
        "point_num_online":2,"point_num_offline":0}],
        "run_zone_latlng":["113.99,29.99","114.01,29.99","114.01,30.01","113.99,30.01"],
        "run_zone_latlng_invalid":[],"run_line_info":{"point_min_distance":0.01,"point_max_distance1":1,
        "point_max_distance2":1,"point_update_distance":0.5,"point_list":[
        {"id":"p0","jingwei":"30.0,114.0","type":2,"point_type":1,"is_online":1,"is_del":0,"ctrl_status":1},
        {"id":"p1","jingwei":"30.001,114.0","type":2,"point_type":1,"is_online":1,"is_del":0,"ctrl_status":1},
        {"id":"p2","jingwei":"30.002,114.0","type":2,"point_type":1,"is_online":1,"is_del":0,"ctrl_status":1}]}}""")
    private val policy=PointPolicyFactory.create(config,time,metric)
    private val nodes=ConfigurationReviewer.review(config).candidates!!.associate { it.id to it.coordinate }
    private val scope=CandidateRoadRequest.create(config,1,"p0","p1").scope
    private val selected=PointSelectionCache(policy.scope(1),day,PlatformOrigin(nodes.getValue("p0")),listOf("p1","p2"))
    private fun edge(a:String,b:String,m:Double)=ObservedRoadEdge(a,b,listOf(nodes.getValue(a),nodes.getValue(b)),m)
    private fun graph(vararg edges:ObservedRoadEdge)=ObservedRoadGraph(scope,"a".repeat(64),nodes,edges.toList(),emptySet(),emptySet())
    private fun plan(g:ObservedRoadGraph, cache:PointSelectionCache=selected, cfg:io.github.ibudaoa.diagnostics.DiagnosticConfig=config,
        region:RouteRegionMode=RouteRegionMode.REQUIRE_BOUNDARY, order:RouteOrderMode=RouteOrderMode.FREE, start:String="p0",
        expansions:Int=50_000,hops:Int=256)=FixedPointRoutePlanner.plan(cfg,policy,cache,day,time,g,start,region,order,expansions,hops)

    @Test fun `free order optimizes the fixed required set without forcing a return`() {
        val g=graph(edge("p0","p1",50.0),edge("p0","p2",90.0),edge("p1","p2",70.0),edge("p2","p1",20.0))
        val draft=(plan(g) as RoutePlanResult.Draft).route
        assertEquals(listOf("p1","p2"),draft.selectedIds)
        assertEquals(listOf("p2","p1"),draft.visitedOrder)
        assertEquals(110.0,draft.lowerDistanceMeters,0.001)
        assertEquals("p1",draft.legs.last().toId)
        assertEquals(listOf("p1","p2"),selected.selectedIds)
    }
    @Test fun `ordered mode retains order and never borrows free order optimization`() {
        val g=graph(edge("p0","p1",50.0),edge("p0","p2",90.0),edge("p1","p2",70.0),edge("p2","p1",20.0))
        val draft=(plan(g,order=RouteOrderMode.ORDERED) as RoutePlanResult.Draft).route
        assertEquals(listOf("p1","p2"),draft.visitedOrder); assertEquals(120.0,draft.lowerDistanceMeters,0.001)
    }
    @Test fun `minimum distance uses only observed directed cycles`() {
        val g=graph(edge("p0","p1",50.0),edge("p1","p2",40.0),edge("p2","p1",40.0))
        val draft=(plan(g) as RoutePlanResult.Draft).route
        assertEquals(130.0,draft.lowerDistanceMeters,0.001)
        assertEquals(listOf("p1","p2","p1"),draft.legs.map { it.toId })
    }
    @Test fun `unavailable route and reverse edges do not become invented paths`() {
        val g=graph(edge("p1","p0",150.0),edge("p2","p1",150.0))
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.NO_OBSERVED_ROUTE),plan(g))
    }
    @Test fun `unknown rules or origin cannot yield a draft`() {
        val g=graph(edge("p0","p1",50.0),edge("p1","p2",70.0))
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.REGION_RULE_UNKNOWN),plan(g,region=RouteRegionMode.UNKNOWN))
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.ORDER_RULE_UNKNOWN),plan(g,order=RouteOrderMode.UNKNOWN))
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.START_NOT_ON_ROAD),
            plan(g,cache=selected.copy(origin=PlatformOrigin(CampusCoordinate(30.000001,114.0)))))
    }
    @Test fun `old area changed config or altered point set is rejected`() {
        val g=graph(edge("p0","p1",50.0),edge("p1","p2",70.0))
        val changed=DiagnosticCodec.decode(config.config.toString().replace("\"run_zone_id\":3","\"run_zone_id\":4"))
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.CONTEXT_CHANGED),plan(g,cfg=changed))
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.INVALID_POINTS),plan(g,cache=selected.copy(selectedIds=listOf("p1","p1"))))
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.CONTEXT_CHANGED),plan(g,cache=selected.copy(selectedOn=day.minusDays(1))))
    }
    @Test fun `locked active point set requires reuse of the existing plan`() {
        val g=graph(edge("p0","p1",50.0),edge("p1","p2",70.0))
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.ACTIVE_PLAN_REUSE_REQUIRED),
            plan(g,cache=selected.lockForExecution()))
    }
    @Test fun `changed time rule cannot reuse the previously planned point scope`() {
        val g=graph(edge("p0","p1",50.0),edge("p1","p2",70.0))
        val changed=DiagnosticCodec.decode(config.config.toString().replace("\"min_distance\":0.1","\"min_distance\":0.2"))
        val newScope=CandidateRoadRequest.create(changed,1,"p0","p1").scope
        val newGraph=ObservedRoadGraph(newScope,"b".repeat(64),nodes,g.edges,emptySet(),emptySet())
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.CONTEXT_CHANGED),plan(newGraph,cfg=changed))
    }

    @Test fun `search limit is explicit and never becomes a fallback route`() {
        val g=graph(edge("p0","p1",50.0),edge("p1","p2",70.0))
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.SEARCH_LIMIT),plan(g,expansions=1))
    }
    @Test fun `an outside required point is never replaced to satisfy a strict region`() {
        val changed=DiagnosticCodec.decode(config.config.toString().replace("114.01,30.01", "114.01,30.0015")
            .replace("113.99,30.01", "113.99,30.0015"))
        val freshScope=CandidateRoadRequest.create(changed,1,"p0","p1").scope
        val g=ObservedRoadGraph(freshScope,"b".repeat(64),nodes,listOf(edge("p0","p1",50.0),edge("p1","p2",70.0)),emptySet(),emptySet())
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.CHECKPOINT_OUTSIDE),plan(g,cfg=changed))
        val draft=(plan(g,cfg=changed,region=RouteRegionMode.CLIENT_FENCE_DISABLED) as RoutePlanResult.Draft).route
        assertEquals(listOf("p1","p2"),draft.selectedIds)
    }
    @Test fun `too narrow an allowed distance never discards the safety margin`() {
        val changed=DiagnosticCodec.decode(config.config.toString().replace("\"min_distance\":0.1", "\"min_distance\":0.5")
            .replace("\"max_distance\":1", "\"max_distance\":0.505"))
        val current=PointPolicyFactory.create(changed,time,metric)
        val cache=selected.copy(scope=current.scope(1))
        val freshScope=CandidateRoadRequest.create(changed,1,"p0","p1").scope
        val g=ObservedRoadGraph(freshScope,"c".repeat(64),nodes,listOf(edge("p0","p1",500.0),edge("p1","p2",1.0)),emptySet(),emptySet())
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.NO_DISTANCE_MARGIN),
            FixedPointRoutePlanner.plan(changed,current,cache,day,time,g,"p0",RouteRegionMode.REQUIRE_BOUNDARY,RouteOrderMode.FREE))
    }
    @Test fun `front end builder uses captured fixed points and refuses an unconnected real origin`() {
        val graph=graph(edge("p0","p1",50.0),edge("p1","p2",70.0))
        val now=java.time.Instant.parse("2026-10-28T00:20:00Z")
        val zone=java.time.ZoneId.of("Asia/Shanghai")
        assertTrue(RouteDraftBuilder.build(config,true,selected,graph,metric,now,zone) is RoutePlanResult.Draft)
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.CONTEXT_CHANGED),
            RouteDraftBuilder.build(config,false,selected,graph,metric,now,zone))
        val offRoad=selected.copy(origin=PlatformOrigin(CampusCoordinate(30.000001,114.0)))
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.START_NOT_ON_ROAD),
            RouteDraftBuilder.build(config,true,offRoad,graph,metric,now,zone))
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.ACTIVE_PLAN_REUSE_REQUIRED),
            RouteDraftBuilder.build(config,true,selected.lockForExecution(),graph,metric,now,zone))
    }

    @Test fun `hop limit returns unknown rather than incorrectly declaring no route`() {
        val g=graph(edge("p0","p1",50.0),edge("p1","p2",70.0))
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.SEARCH_LIMIT),plan(g,hops=1))
    }

    @Test fun `unknown forbidden region blocks even if observed roads appear connected`() {
        val changed=DiagnosticCodec.decode(config.config.toString().replace("\"run_zone_latlng_invalid\":[]","\"run_zone_latlng_invalid\":[\"114,30\"]"))
        val freshScope=CandidateRoadRequest.create(changed,1,"p0","p1").scope
        val g=ObservedRoadGraph(freshScope,"b".repeat(64),nodes,listOf(edge("p0","p1",50.0),edge("p1","p2",70.0)),emptySet(),emptySet())
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.FORBIDDEN_REGION_UNKNOWN),plan(g,cfg=changed))
    }
    @Test fun `road anchors within client radius keep checkpoints unchanged and use only road length`() {
        val result = offsetPlan()
        val route = (result as RoutePlanResult.Draft).route
        assertEquals(listOf("p1", "p2"), route.selectedIds)
        assertEquals(listOf("p1", "p2"), route.visitedOrder)
        assertEquals(114.0001, route.legs.first().points.first().longitude, 0.0)
        assertTrue(route.lowerDistanceMeters in 220.0..224.0)
        assertEquals(114.0, nodes.getValue("p1").longitude, 0.0)
    }

    @Test fun `two different road anchors near the same checkpoint cannot teleport`() {
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.NO_OBSERVED_ROUTE), offsetPlan(splitAnchor = true))
    }

    @Test fun `nearby GPS is not silently snapped to an offset road anchor`() {
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.START_NOT_ON_ROAD), offsetPlan(offRoadOrigin = true))
    }

    @Test fun `unknown checkpoint radius still rejects offset roads`() {
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.START_NOT_ON_ROAD), offsetPlan(knownRadius = false))
    }

    @Test fun `known but insufficient checkpoint radius does not admit an offset road`() {
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.START_NOT_ON_ROAD), offsetPlan(radius = 1))
    }

    private fun offsetPlan(splitAnchor: Boolean = false, offRoadOrigin: Boolean = false,
        knownRadius: Boolean = true, radius: Int = 20): RoutePlanResult {
        val cfg = DiagnosticCodec.decode(config.config.toString().replace("\"run_line_info\":{",
            "\"is_location_white_list\":${if (knownRadius) 0 else 9},\"run_line_info\":{\"log_max_distance\":$radius,"))
        val current = PointPolicyFactory.create(cfg, time, metric)
        fun shifted(id: String, extra: Double = 0.0) = nodes.getValue(id).copy(longitude = 114.0001 + extra)
        val a = shifted("p0"); val b = shifted("p1"); val c = shifted("p2")
        val distance = io.github.ibudaoa.maps.AmapDistance()
        fun leg(points: List<CampusCoordinate>) = WalkingLeg(points.zipWithNext().sumOf { (x, y) -> distance.meters(x,y) }, listOf(points))
        val evidence = RoadEvidenceSnapshot(CandidateRoadRequest.create(cfg, 1, "p0", "p1").scope, listOf(
            CandidateRoadEvidence("p0", "p1", leg(listOf(a,b))),
            CandidateRoadEvidence("p1", "p2", leg(listOf(shifted("p1", if (splitAnchor) 0.00001 else 0.0),c)))))
        val g = ObservedRoadGraphBuilder.build(cfg, evidence, distance)
        val cache = selected.copy(scope = current.scope(1), origin = PlatformOrigin(
            if (offRoadOrigin) a.copy(longitude = a.longitude + 0.000001) else a))
        return FixedPointRoutePlanner.plan(cfg, current, cache, day, time, g, "p0",
            RouteRegionMode.REQUIRE_BOUNDARY, RouteOrderMode.FREE)
    }

    @Test fun `exact mid-road origin uses only the forward observed suffix`() {
        val g = graph(edge("p0", "p1", 111.2), edge("p1", "p2", 111.2))
        val origin = CampusCoordinate(30.0005, 114.0)
        val cache = selected.copy(origin = PlatformOrigin(origin))
        val now = java.time.Instant.parse("2026-10-28T00:20:00Z")
        val metric = io.github.ibudaoa.maps.AmapDistance()
        val result = RouteDraftBuilder.build(config, true, cache, g, metric.implementationId, now,
            java.time.ZoneId.of("Asia/Shanghai"), metric)
        val route = (result as RoutePlanResult.Draft).route
        assertEquals(origin, route.legs.first().points.first())
        assertEquals(nodes.getValue("p1"), route.legs.first().points.last())
        assertTrue(route.lowerDistanceMeters in 165.0..168.0)
        assertEquals(listOf("p1", "p2"), route.visitedOrder)
    }

    @Test fun `nearby mid-road origin and a reverse-only route remain blocked`() {
        val metric = io.github.ibudaoa.maps.AmapDistance()
        val now = java.time.Instant.parse("2026-10-28T00:20:00Z")
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val forward = graph(edge("p0", "p1", 111.2), edge("p1", "p2", 111.2))
        val nearby = selected.copy(origin = PlatformOrigin(CampusCoordinate(30.0005, 114.000001)))
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.START_NOT_ON_ROAD),
            RouteDraftBuilder.build(config, true, nearby, forward, metric.implementationId, now, zone, metric))
        val reverse = graph(edge("p1", "p0", 111.2), edge("p2", "p1", 111.2))
        val origin = selected.copy(origin = PlatformOrigin(CampusCoordinate(30.0005, 114.0)))
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.NO_OBSERVED_ROUTE),
            RouteDraftBuilder.build(config, true, origin, reverse, metric.implementationId, now, zone, metric))
    }

    @Test fun `origin connector is unavailable when distance implementation does not match`() {
        val g = graph(edge("p0", "p1", 111.2), edge("p1", "p2", 111.2))
        val other = object : PlatformDistance {
            override val implementationId = "other"
            override fun meters(from: CampusCoordinate, to: CampusCoordinate) = 1.0
        }
        assertNull(ObservedOriginAccess.find(g, CampusCoordinate(30.0005, 114.0), other))
    }

    @Test fun `reviewed two-way roads allow the same fixed checkpoints via the shared road hub`() {
        val oneWay = graph(edge("p0", "p1", 50.0), edge("p0", "p2", 90.0))
        assertEquals(RoutePlanResult.Blocked(RoutePlanProblem.NO_OBSERVED_ROUTE), plan(oneWay))
        val review = ReviewedRoadDirections(scope, oneWay.fingerprint, null, setOf("p0" to "p1"))
        val both = ReviewedRoadDirectionsBuilder.apply(oneWay, review, null)
        val route = (plan(both) as RoutePlanResult.Draft).route
        assertEquals(listOf("p1", "p2"), route.visitedOrder)
        assertEquals(listOf("p1", "p0", "p2"), route.legs.map { it.toId })
        assertEquals(190.0, route.lowerDistanceMeters, 0.001)
        assertTrue(route.includesReviewedDirections)
        assertEquals(oneWay.edges.first().points.reversed(), both.edges.last().points)
    }

    @Test fun `two-way review cannot be transferred to changed roads or different gap decisions`() {
        val g = graph(edge("p0", "p1", 50.0), edge("p0", "p2", 90.0))
        val manual = ManualRoadGaps(g.fingerprint, setOf(0))
        val reviewedGraph = ObservedRoadGraph(g.scope, g.fingerprint, g.nodes, g.edges, g.unjoinedLegs, g.offsetLegs,
            manualConfirmationCount = 1)
        val review = ReviewedRoadDirections(scope, g.fingerprint, ManualRoadGapsCodec.encode(manual), setOf("p0" to "p1"))
        assertTrue(runCatching { ReviewedRoadDirectionsBuilder.apply(reviewedGraph, review, manual.copy(indices = setOf(1))) }.isFailure)
        assertTrue(runCatching { ReviewedRoadDirectionsBuilder.apply(reviewedGraph, review.copy(evidenceFingerprint = "b".repeat(64)), manual) }.isFailure)
        assertTrue(runCatching { ReviewedRoadDirectionsBuilder.apply(reviewedGraph, review.copy(reversible = setOf("p1" to "p0")), manual) }.isFailure)
        assertEquals(2, g.edges.size)
    }

    @Test fun `after all fixed checkpoints the return leg may finish mid road at the distance margin`() {
        val cfg = DiagnosticCodec.decode(config.config.toString().replace("\"min_distance\":0.1", "\"min_distance\":0.3"))
        val current = PointPolicyFactory.create(cfg, time, metric)
        val cache = selected.copy(scope = current.scope(1))
        val fresh = CandidateRoadRequest.create(cfg, 1, "p0", "p1").scope
        val distance = io.github.ibudaoa.maps.AmapDistance()
        fun actual(a: String, b: String) = ObservedRoadEdge(a, b, listOf(nodes.getValue(a), nodes.getValue(b)),
            distance.meters(nodes.getValue(a), nodes.getValue(b)))
        val graph = ObservedRoadGraph(fresh, "c".repeat(64), nodes,
            listOf(actual("p0", "p1"), actual("p1", "p2"), actual("p2", "p1")), emptySet(), emptySet())
        val route = (FixedPointRoutePlanner.plan(cfg, current, cache, day, time, graph, "p0",
            RouteRegionMode.REQUIRE_BOUNDARY, RouteOrderMode.FREE, originDistance = distance) as RoutePlanResult.Draft).route
        assertEquals(listOf("p1", "p2"), route.visitedOrder)
        assertEquals(listOf("p1", "p2", "@finish"), route.legs.map { it.toId })
        assertTrue(route.lowerDistanceMeters >= route.minimumTargetMeters)
        assertTrue(route.upperDistanceMeters < route.minimumTargetMeters + 0.1)
        assertTrue(route.legs.last().points.last().latitude in 30.001..30.002)
        assertEquals(listOf("p1", "p2"), cache.selectedIds)
    }

}

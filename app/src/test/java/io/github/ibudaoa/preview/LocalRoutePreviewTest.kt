package io.github.ibudaoa.preview

import io.github.ibudaoa.diagnostics.DiagnosticCodec
import io.github.ibudaoa.diagnostics.PreparationExport
import io.github.ibudaoa.domain.*
import io.github.ibudaoa.maps.*
import io.github.ibudaoa.roads.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalRoutePreviewTest {
    private val config=DiagnosticCodec.decode("""{"school_id":1,"term_id":2,"run_zone_id":3,
      "role":1,"authStatus":2,"set_point_type":1,"point_type":1,"repeat_num":0,"log_mode":1,"log_logic":1,"is_limit_range":0,
      "term_start_time":1767225600,"term_end_time":1798761600,"run_face_verify_config":{},
      "time_rule_arr":[{"start_time":21600,"end_time":79200,"min_distance":0.3,"max_distance":1,
        "max_pace":180,"min_pace":540,"min_log_num":2,"point_num_online":2,"point_num_offline":0}],
      "run_zone_latlng":["113.99,29.99","114.01,29.99","114.01,30.01","113.99,30.01"],"run_zone_latlng_invalid":[],
      "run_line_info":{"point_min_distance":0.01,"point_max_distance1":1,"point_max_distance2":1,"point_update_distance":0.5,"point_list":[
        {"id":"p0","jingwei":"30.0,114.0","type":2,"point_type":1,"is_online":1,"is_del":0,"ctrl_status":1},
        {"id":"p1","jingwei":"30.001,114.0","type":2,"point_type":1,"is_online":1,"is_del":0,"ctrl_status":1},
        {"id":"p2","jingwei":"30.002,114.0","type":2,"point_type":1,"is_online":1,"is_del":0,"ctrl_status":1}]}}""")
    private val metric=AmapDistance()
    private val nodes=ConfigurationReviewer.review(config).candidates!!.associate { it.id to it.coordinate }
    private val scope=CandidateRoadRequest.create(config,1,"p0","p1").scope
    private fun edge(a:String,b:String)=ObservedRoadEdge(a,b,listOf(nodes.getValue(a),nodes.getValue(b)),metric.meters(nodes.getValue(a),nodes.getValue(b)))
    private val graph=ObservedRoadGraph(scope,"a".repeat(64),nodes,
        listOf(edge("p0","p1"),edge("p1","p0"),edge("p1","p2"),edge("p2","p1")),emptySet(),emptySet(),reviewedTwoWayRoadCount=2)
    private val now=Instant.parse("2026-10-28T00:20:00Z")
    private val zone=ZoneId.of("Asia/Shanghai")
    private val fix=DisplayFix(CampusCoordinate(30.0005,114.00001),3f,1000)
    private fun prepare(cached:PointSelectionCache?=null,position:DisplayFix?=fix,elapsed:Long=1001)=
        LocalRoutePreviewEngine.prepare(config,true,graph,position,cached,now,elapsed,zone,metric)

    @Test fun `local preview waits for origin review then visits the fixed points with no graph mutation`() {
        val pending=(prepare() as PreviewPreparation.Ready).preview
        assertNull(pending.route)
        assertEquals(2,pending.points.selectedIds.size)
        val (preview,summary)=LocalRoutePreviewEngine.confirm(config,true,graph,pending,fix,now,1002,zone,metric)
        assertEquals(PreviewPhase.DRAFT,summary.phase)
        assertTrue(summary.originReviewed)
        val route=preview!!.route!!
        assertEquals(fix.coordinate,route.legs.first().points.first())
        assertEquals(pending.points.selectedIds.toSet(),route.visitedOrder.toSet())
        assertTrue(route.includesReviewedOrigin)
        assertTrue(route.lowerDistanceMeters>=route.minimumTargetMeters)
        assertEquals(4,graph.edges.size)
    }

    @Test fun `stale or missing location cannot allocate a new preview`() {
        assertEquals(PreviewPreparation.Blocked(PreviewProblem.LOCATION_REQUIRED),prepare(position=null))
        assertEquals(PreviewPreparation.Blocked(PreviewProblem.LOCATION_REQUIRED),prepare(elapsed=20_000))
        assertEquals(PreviewPreparation.Blocked(PreviewProblem.LOCATION_REQUIRED),prepare(position=fix.copy(accuracyMeters=51f)))
    }

    @Test fun `repeated preview reuses rule selected points while route origin can be reviewed anew`() {
        val first=(prepare() as PreviewPreparation.Ready).preview
        val moved=fix.copy(coordinate=CampusCoordinate(30.00051,114.00001))
        val second=(prepare(first.points,moved) as PreviewPreparation.Ready).preview
        assertEquals(first.points,second.points)
        val (preview,summary)=LocalRoutePreviewEngine.confirm(config,true,graph,second,moved,now,1002,zone,metric)
        assertEquals(PreviewPhase.DRAFT,summary.phase)
        assertEquals(moved.coordinate,preview!!.route!!.legs.first().points.first())
        assertEquals(first.points.origin,preview.points.origin)
    }

    @Test fun `changed actual directed roads invalidate a pending approval`() {
        val pending=(prepare() as PreviewPreparation.Ready).preview
        val changed=ObservedRoadGraph(scope,graph.fingerprint,nodes,graph.edges.drop(1),emptySet(),emptySet(),reviewedTwoWayRoadCount=1)
        val (_,summary)=LocalRoutePreviewEngine.confirm(config,true,changed,pending,fix,now,1002,zone,metric)
        assertEquals(PreviewProblem.CONTEXT_CHANGED,summary.problem)
    }

    @Test fun `moving away or crossing a time boundary requires renewed preparation`() {
        val pending=(prepare() as PreviewPreparation.Ready).preview
        val moved=fix.copy(coordinate=CampusCoordinate(30.0006,114.00001))
        val (_,motion)=LocalRoutePreviewEngine.confirm(config,true,graph,pending,moved,now,1002,zone,metric)
        assertEquals(PreviewProblem.POSITION_CHANGED,motion.problem)
        val (_,time)=LocalRoutePreviewEngine.confirm(config,true,graph,pending,fix,Instant.parse("2026-10-28T15:00:00Z"),1002,zone,metric)
        assertEquals(PreviewProblem.RULES_UNAVAILABLE,time.problem)
    }

    @Test fun `far away or unverified configuration cannot become a route proposal`() {
        assertTrue(prepare(position=fix.copy(coordinate=CampusCoordinate(30.1,114.1))) is PreviewPreparation.Blocked)
        assertEquals(PreviewPreparation.Blocked(PreviewProblem.CONTEXT_CHANGED),
            LocalRoutePreviewEngine.prepare(config,false,graph,fix,null,now,1002,zone,metric))
    }

    @Test fun `export contains summary only and never the private origin or connector geometry`() {
        val pending=(prepare() as PreviewPreparation.Ready).preview
        val (_,summary)=LocalRoutePreviewEngine.confirm(config,true,graph,pending,fix,now,1002,zone,metric)
        val batch=RoadBatchPlan.create(config,1)
        val report=RoadBatchReport(scope,RoadBatchPhase.INTERRUPTED,batch.candidateCount,batch.requests.size)
        val text=PreparationExport.encode(config,1,1,report,graph=graph,preview=summary)
        assertTrue(text.contains("local_route_preview"))
        assertTrue(text.contains("\"selected_count\": 2"))
        assertFalse(text.contains("30.0005"));assertFalse(text.contains("114.00001"))
        assertFalse(text.contains("roadPoint"));assertFalse(text.contains("accesses"))
        assertFalse(PreparationExport.encode(config,1,1,report,graph=graph,
            preview=summary.copy(scope=scope.copy(connectionEpoch=2))).contains("local_route_preview"))
    }

    @Test fun `origin projection offers both reviewed directions without extrapolating the original road`() {
        val proposal=OriginRoadProposalBuilder.create(graph,fix.coordinate,metric)!!
        val accesses=ReviewedOriginAccess.approve(proposal).edges(graph,fix.coordinate,graph.edges)!!
        assertEquals(setOf("p0","p1"),accesses.map { it.toId }.toSet())
        assertTrue(proposal.roadPoint.latitude in 30.0..30.001)
        assertEquals(114.0,proposal.roadPoint.longitude,0.0)
        assertTrue(proposal.gapMeters in 0.5..2.0)
    }
    @Test fun `a later position sample is rechecked before a computed preview may be displayed`() {
        val pending=(prepare() as PreviewPreparation.Ready).preview
        assertNull(LocalRoutePreviewEngine.reviewProblem(config,true,graph,pending,fix,now,1002,zone,metric))
        val drift=fix.copy(coordinate=CampusCoordinate(30.0006,114.00001),receivedElapsedMillis=1002)
        assertEquals(PreviewProblem.POSITION_CHANGED,
            LocalRoutePreviewEngine.reviewProblem(config,true,graph,pending,drift,now,1003,zone,metric))
        assertEquals(PreviewProblem.LOCATION_REQUIRED,
            LocalRoutePreviewEngine.reviewProblem(config,true,graph,pending,fix,now,30_000,zone,metric))
    }

    @Test fun `a one way road proposal never receives an implicit reverse access`() {
        val oneWay=ObservedRoadGraph(scope,graph.fingerprint,nodes,listOf(edge("p0","p1")),emptySet(),emptySet())
        val proposal=OriginRoadProposalBuilder.create(oneWay,fix.coordinate,metric)!!
        val accesses=ReviewedOriginAccess.approve(proposal).edges(oneWay,fix.coordinate,oneWay.edges)!!
        assertEquals(listOf("p1"),accesses.map { it.toId })
        assertNull(ReviewedOriginAccess.approve(proposal).edges(graph,fix.coordinate,graph.edges))
    }

    @Test fun `point preview must not use a new configuration with a previously observed road graph`() {
        val changed=DiagnosticCodec.decode(config.config.toString().replace("\"run_zone_id\":3","\"run_zone_id\":4"))
        assertEquals(PreviewPreparation.Blocked(PreviewProblem.CONTEXT_CHANGED),
            LocalRoutePreviewEngine.prepare(changed,true,graph,fix,null,now,1002,zone,metric))
    }

    @Test fun `eleven reversible road branches do not exhaust search before filling the minimum distance`() {
        val coordinates=(0..11).associate { i ->
            "s$i" to if (i==0) CampusCoordinate(30.0,114.0) else CampusCoordinate(
                30.0+(0.0007+i*0.00031)*kotlin.math.cos(i*0.53),
                114.0+(0.0007+i*0.00031)*kotlin.math.sin(i*0.53))
        }
        val root=config.config
        val line=root.getValue("run_line_info").jsonObject
        val points=buildJsonArray { coordinates.forEach { (id,p) -> add(buildJsonObject {
            put("id",id);put("jingwei","${p.latitude},${p.longitude}");put("type",2);put("point_type",1)
            put("is_online",1);put("is_del",0);put("ctrl_status",1)
        }) } }
        val cfg=DiagnosticCodec.decode(JsonObject(root+mapOf("run_line_info" to JsonObject(line+mapOf("point_list" to points)),
            "time_rule_arr" to JsonArray(root.getValue("time_rule_arr").jsonArray.map {
                JsonObject(it.jsonObject+mapOf("min_distance" to JsonPrimitive(2),"max_distance" to JsonPrimitive(10)))
            }))).toString())
        val current=PointPolicyFactory.create(cfg,30_000,metric.implementationId)
        val currentScope=CandidateRoadRequest.create(cfg,1,"s0","s1").scope
        val edges=(1..11).flatMap { i ->
            val a=coordinates.getValue("s0");val b=coordinates.getValue("s$i");val meters=metric.meters(a,b)
            listOf(ObservedRoadEdge("s0","s$i",listOf(a,b),meters),ObservedRoadEdge("s$i","s0",listOf(b,a),meters))
        }
        val roads=ObservedRoadGraph(currentScope,"d".repeat(64),coordinates,edges,emptySet(),emptySet(),reviewedTwoWayRoadCount=11)
        val selected=PointSelectionCache(current.scope(1),now.atZone(zone).toLocalDate(),PlatformOrigin(coordinates.getValue("s0")),listOf("s10","s11"))
        val result=FixedPointRoutePlanner.plan(cfg,current,selected,now.atZone(zone).toLocalDate(),30_000,roads,"s0",
            RouteRegionMode.CLIENT_FENCE_DISABLED,RouteOrderMode.FREE,originDistance=metric)
        assertTrue("Expected a bounded draft, got $result",result is RoutePlanResult.Draft)
        val route=(result as RoutePlanResult.Draft).route
        assertTrue(route.lowerDistanceMeters>=route.minimumTargetMeters)
        assertTrue(route.upperDistanceMeters<route.minimumTargetMeters+0.1)
    }

}

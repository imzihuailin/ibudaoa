package io.github.ibudaoa.running

import io.github.ibudaoa.domain.*
import io.github.ibudaoa.maps.*
import io.github.ibudaoa.preview.*
import io.github.ibudaoa.roads.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class OriginAccessTest {
    private val config=RunningPlanTest().config
    private val metric=AmapDistance()
    private val nodes=ConfigurationReviewer.review(config).candidates!!.associate { it.id to it.coordinate }
    private val scope=CandidateRoadRequest.create(config,1,"p0","p1").scope
    private fun edge(a:String,b:String)=ObservedRoadEdge(a,b,listOf(nodes.getValue(a),nodes.getValue(b)),metric.meters(nodes.getValue(a),nodes.getValue(b)))
    private val graph=ObservedRoadGraph(scope,"a".repeat(64),nodes,listOf(edge("p0","p1"),edge("p1","p0"),edge("p1","p2"),edge("p2","p1")),emptySet(),emptySet(),reviewedTwoWayRoadCount=2)
    private val origin=CampusCoordinate(30.0005,114.0028)
    private fun length(points:List<CampusCoordinate>)=points.zipWithNext().sumOf { metric.meters(it.first,it.second) }
    private fun target()=OriginRoadProposalBuilder.queryTarget(graph,origin,metric)!!
    private fun walk(target:OriginRoadProposal):WalkingLeg {
        val path=listOf(origin,CampusCoordinate(30.001,origin.longitude),CampusCoordinate(30.001,114.0),target.roadPoint)
        return WalkingLeg(length(path),listOf(path))
    }
    @Test fun `far start uses the complete walking access and visits all fixed points`() {
        assertNull(OriginRoadProposalBuilder.create(graph,origin,metric)) // Old local-only budget remains a debug-only helper.
        val target=target();assertTrue(target.gapMeters>50)
        val leg=walk(target)
        val access=OriginRoadProposalBuilder.routed(graph,target,listOf(leg),metric)!!
        assertEquals(leg.distanceMeters,access.gapMeters,0.001)
        assertEquals(OriginAccessSource.SDK_GUIDANCE,access.source)
        assertTrue(access.gapMeters>metric.meters(origin,target.roadPoint))
        val now=Instant.parse("2026-10-28T00:20:00Z");val zone=ZoneId.of("Asia/Shanghai")
        val fix=DisplayFix(origin,3f,1000)
        val pending=(LocalRoutePreviewEngine.prepare(config,true,graph,fix,null,now,1001,zone,metric,access) as PreviewPreparation.Ready).preview
        val (preview,summary)=LocalRoutePreviewEngine.confirm(config,true,graph,pending,fix,now,1002,zone,metric)
        val route=preview!!.route!!
        assertEquals(PreviewPhase.DRAFT,summary.phase)
        assertEquals(origin,route.legs.first().points.first())
        assertEquals(access.approach,route.legs.first().points.take(access.approach.size))
        assertEquals(leg.distanceMeters,route.originAccessMeters,0.001)
        assertTrue(route.lowerDistanceMeters>leg.distanceMeters)
        assertEquals(pending.points.selectedIds.toSet(),route.visitedOrder.toSet())
        assertEquals(leg.distanceMeters.toInt()+1,RunningPlanRules.create(config,route,now,zone).second.accessDistanceMeters)
        assertEquals(4,graph.edges.size)
    }
    @Test fun `access cannot silently skip large gaps wrong geometry or stale graph`() {
        val target=target();val good=walk(target)
        assertNull(OriginRoadProposalBuilder.routed(graph,target,listOf(good.copy(distanceMeters=1.0)),metric))
        assertNull(OriginRoadProposalBuilder.routed(graph,target,listOf(good.copy(steps=listOf(listOf(CampusCoordinate(31.0,115.0),target.roadPoint)))),metric))
        val changed=ObservedRoadGraph(scope,graph.fingerprint,nodes,graph.edges.drop(1),emptySet(),emptySet())
        assertNull(OriginRoadProposalBuilder.routed(changed,target,listOf(good),metric))
        assertNull(OriginRoadProposalBuilder.routed(graph,target,emptyList(),metric))
    }
    @Test fun `source walking bends are retained and reverse access is not inferred`() {
        val oneWay=ObservedRoadGraph(scope,graph.fingerprint,nodes,listOf(edge("p0","p1"),edge("p1","p2")),emptySet(),emptySet())
        val target=OriginRoadProposalBuilder.queryTarget(oneWay,origin,metric)!!
        val access=OriginRoadProposalBuilder.routed(oneWay,target,listOf(walk(target)),metric)!!
        val edges=ReviewedOriginAccess.approve(access).edges(oneWay,origin,oneWay.edges)!!
        assertTrue(edges.all { it.fromId=="@origin" && it.toId=="p1" })
        assertEquals(4,access.approach.size)
        assertEquals(CampusCoordinate(30.001,origin.longitude),access.approach[1])
    }
    @Test fun `movement while requesting access requires renewed origin review`() {
        val target=target();val access=OriginRoadProposalBuilder.routed(graph,target,listOf(walk(target)),metric)!!
        val moved=DisplayFix(CampusCoordinate(origin.latitude+0.0002,origin.longitude),3f,1000)
        val result=LocalRoutePreviewEngine.prepare(config,true,graph,moved,null,Instant.parse("2026-10-28T00:20:00Z"),1001,ZoneId.of("Asia/Shanghai"),metric,access)
        assertEquals(PreviewPreparation.Blocked(PreviewProblem.POSITION_CHANGED),result)
    }
}

package io.github.ibudaoa.roads

import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.domain.PlatformDistance
import kotlin.math.cos

/** Memory-only proposal, never platform GPS or a persisted track. */
data class OriginRoadProposal internal constructor(
    val scope: RoadScope, val graphIdentity: String,
    val origin: CampusCoordinate, val roadPoint: CampusCoordinate, val gapMeters: Double,
    internal val accesses: List<Pair<ObservedRoadEdge, ObservedRoadEdge>>,
    val approach:List<CampusCoordinate> = listOf(origin,roadPoint),
    val source:OriginAccessSource=OriginAccessSource.USER_REVIEWED,
)
enum class OriginAccessSource { USER_REVIEWED, SDK_GUIDANCE }

class ReviewedOriginAccess private constructor(internal val proposal: OriginRoadProposal) {
    companion object {
        /** Call only after the user has inspected this exact position and connector in the foreground. */
        fun approve(proposal: OriginRoadProposal): ReviewedOriginAccess = ReviewedOriginAccess(proposal)
    }
    internal fun edges(graph: ObservedRoadGraph, origin: CampusCoordinate, allowed: List<ObservedRoadEdge>): List<ObservedRoadEdge>? =
        proposal.accesses.takeIf { proposal.scope == graph.scope && proposal.origin == origin &&
            proposal.graphIdentity == GraphGeometryIdentity.of(graph) }?.filter { it.first in allowed }?.map { it.second }
}

object OriginRoadProposalBuilder {
    private const val MAX_POINTS = 50_000
    private const val MAX_ACCESS_POINTS = 50_000
    const val MAX_GAP_METERS = 50.0 // Local review budget, never a school or platform rule.
    fun create(graph: ObservedRoadGraph, origin: CampusCoordinate, distance: PlatformDistance): OriginRoadProposal? =
        build(graph,origin,distance,MAX_GAP_METERS)
    /** Query target only; never approve this unbounded straight-line seed as a walking route. */
    fun queryTarget(graph:ObservedRoadGraph,origin:CampusCoordinate,distance:PlatformDistance):OriginRoadProposal? =
        build(graph,origin,distance,Double.POSITIVE_INFINITY)
    private fun build(graph:ObservedRoadGraph,origin:CampusCoordinate,distance:PlatformDistance,maxGap:Double):OriginRoadProposal? {
        if (distance.implementationId != WalkingRoadReviewer.DISTANCE || !origin.valid() || graph.edges.isEmpty() ||
            graph.edges.sumOf { it.points.size.toLong() } > MAX_POINTS) return null
        data class Hit(val edge: ObservedRoadEdge, val segment: Int, val point: CampusCoordinate, val meters: Double)
        val hits = mutableListOf<Hit>()
        val scale = cos(Math.toRadians(origin.latitude)).coerceAtLeast(0.000001)
        for (edge in graph.edges) {
            if (edge.points.size < 2 || edge.points.any { !it.valid() }) return null
            for (i in 0 until edge.points.lastIndex) {
                val a = edge.points[i]; val b = edge.points[i + 1]
                // Project from one canonical ordering, so both travel directions share the same point.
                val left: CampusCoordinate; val right: CampusCoordinate
                if (a.latitude < b.latitude || a.latitude == b.latitude && a.longitude <= b.longitude) { left=a; right=b }
                else { left=b; right=a }
                val x = (right.longitude-left.longitude)*scale; val y = right.latitude-left.latitude
                val length = x*x+y*y
                if (length == 0.0) continue
                val fraction = (((origin.longitude-left.longitude)*scale*x + (origin.latitude-left.latitude)*y)/length).coerceIn(0.0,1.0)
                val point = when (fraction) { 0.0 -> left; 1.0 -> right; else -> CampusCoordinate(
                    left.latitude+(right.latitude-left.latitude)*fraction,
                    left.longitude+(right.longitude-left.longitude)*fraction) }
                val meters = runCatching { distance.meters(origin, point) }.getOrNull() ?: return null
                if (!meters.isFinite() || meters < 0) return null
                hits.add(Hit(edge,i,point,meters))
            }
        }
        val best = hits.minByOrNull { it.meters } ?: return null
        if (best.meters > maxGap) return null
        var count = 0
        val accesses = hits.filter { it.point == best.point }.mapNotNull { hit ->
            val points = (listOf(origin, best.point) + hit.edge.points.drop(hit.segment+1))
                .fold(mutableListOf<CampusCoordinate>()) { acc, p -> if (acc.lastOrNull()!=p) acc.add(p); acc }
            count += points.size
            if (count > MAX_ACCESS_POINTS || points.size < 2) return@mapNotNull null
            val meters = runCatching { points.zipWithNext().sumOf { (a,b) -> distance.meters(a,b).also { require(it.isFinite() && it >= 0) } } }.getOrNull()
                ?: return null
            if (meters < 0.001) null else hit.edge to ObservedRoadEdge(ObservedOriginAccess.START_ID, hit.edge.toId, points, meters)
        }.distinctBy { it.second.toId to it.second.points }
        if (count > MAX_ACCESS_POINTS || accesses.isEmpty()) return null
        return OriginRoadProposal(graph.scope,GraphGeometryIdentity.of(graph),origin,best.point,best.meters,accesses)
    }
    /** SDK guidance plus short endpoint/step joins reviewed on the map; never an actual recorded track. */
    fun routed(graph:ObservedRoadGraph,target:OriginRoadProposal,paths:List<WalkingLeg>,distance:PlatformDistance):OriginRoadProposal? {
        if(target.scope!=graph.scope || target.graphIdentity!=GraphGeometryIdentity.of(graph) ||
            distance.implementationId!=WalkingRoadReviewer.DISTANCE || paths.size !in 1..3) return null
        val candidates=paths.mapNotNull { leg ->
            if(!leg.distanceMeters.isFinite() || leg.distanceMeters<=0 || leg.steps.isEmpty() ||
                leg.steps.any { it.size<2 || it.any { p -> !p.valid() } } || leg.steps.sumOf { it.size }>20_000) return@mapNotNull null
            fun meters(a:CampusCoordinate,b:CampusCoordinate)=distance.meters(a,b).also { require(it.isFinite() && it>=0) }
            runCatching {
                val raw=leg.steps.flatten()
                val joins=listOf(target.origin to raw.first(),raw.last() to target.roadPoint)+leg.steps.zipWithNext().map { it.first.last() to it.second.first() }
                if(joins.any { meters(it.first,it.second)>MAX_GAP_METERS }) return@runCatching null
                val geometry=leg.steps.sumOf { step -> step.zipWithNext().sumOf { meters(it.first,it.second) } }
                if(kotlin.math.abs(geometry-leg.distanceMeters)>maxOf(10.0,leg.distanceMeters*0.1)) return@runCatching null
                val approach=(listOf(target.origin)+raw+target.roadPoint).fold(mutableListOf<CampusCoordinate>()) { acc,p -> if(acc.lastOrNull()!=p)acc.add(p);acc }
                val approachMeters=approach.zipWithNext().sumOf { meters(it.first,it.second) }
                var count=0
                val accesses=target.accesses.mapNotNull access@ { (base,seed) ->
                    val suffix=seed.points.dropWhile { it!=target.roadPoint }
                    if(suffix.isEmpty()) return@access null
                    val points=approach+suffix.drop(1)
                    count+=points.size
                    if(count>MAX_ACCESS_POINTS) return@runCatching null
                    val total=points.zipWithNext().sumOf { meters(it.first,it.second) }
                    if(total<0.001) null else base to ObservedRoadEdge(ObservedOriginAccess.START_ID,base.toId,points,total)
                }
                if(accesses.isEmpty()) null else OriginRoadProposal(graph.scope,target.graphIdentity,target.origin,target.roadPoint,
                    approachMeters,accesses,approach.toList(),OriginAccessSource.SDK_GUIDANCE)
            }.getOrNull()
        }
        return candidates.minByOrNull { it.gapMeters }
    }
    private fun CampusCoordinate.valid() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}

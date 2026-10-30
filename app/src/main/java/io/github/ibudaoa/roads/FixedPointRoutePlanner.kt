package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.domain.ConfigurationReviewer
import io.github.ibudaoa.domain.PointSelectionCache
import io.github.ibudaoa.domain.PointSelectionPolicy
import io.github.ibudaoa.domain.PointPolicyFactory
import io.github.ibudaoa.domain.PlatformDistance
import java.time.LocalDate
import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.floor

/** These values are supplied only by independently verified school rules, never guessed from field names. */
// CLIENT_FENCE_DISABLED describes the inspected official client only, not server-side acceptance.
enum class RouteRegionMode { UNKNOWN, REQUIRE_BOUNDARY, CLIENT_FENCE_DISABLED }
enum class RouteOrderMode { UNKNOWN, FREE, ORDERED }
enum class RoutePlanProblem {
    CONTEXT_CHANGED, INVALID_POINTS, START_NOT_ON_ROAD, REGION_RULE_UNKNOWN, FORBIDDEN_REGION_UNKNOWN,
    AREA_INVALID, CHECKPOINT_OUTSIDE, ORDER_RULE_UNKNOWN, NO_DISTANCE_MARGIN, NO_OBSERVED_ROUTE, SEARCH_LIMIT, ACTIVE_PLAN_REUSE_REQUIRED,
}
sealed interface RoutePlanResult {
    data class Blocked(val problem: RoutePlanProblem) : RoutePlanResult
    data class Draft(val route: FixedPointRoute) : RoutePlanResult
}

/** A local planning draft only; no claim that the platform task can start or that a record is valid. */
data class FixedPointRoute(
    val scope: RoadScope, val roadFingerprint: String, val selectedIds: List<String>,
    val startId: String, val visitedOrder: List<String>, val legs: List<ObservedRoadEdge>,
    val lowerDistanceMeters: Double, val upperDistanceMeters: Double,
    val minimumTargetMeters: Double, val maximumTargetMeters: Double,
    val includesManualRoadConfirmation: Boolean = false,
    val includesReviewedDirections: Boolean = false,
    val includesReviewedOrigin: Boolean = false,
    val originAccessMeters:Double=0.0,
    val originAccessSource:OriginAccessSource?=null,
)

/** Searches only directed, continuous SDK-observed legs; it cannot create missing paths or change point IDs. */
object FixedPointRoutePlanner {
    private const val MAX_EXPANSIONS = 50_000
    private const val MAX_HOPS = 256
    private data class Key(val node: String, val position: CampusCoordinate, val visited: Int, val lowerMm: Long, val upperMm: Long)
    private data class Label(val key: Key, val hops: Int, val parent: Label?, val edge: ObservedRoadEdge?)

    fun plan(config: DiagnosticConfig, policy: PointSelectionPolicy, selected: PointSelectionCache,
        day: LocalDate, secondOfDay: Int, graph: ObservedRoadGraph, startId: String,
        regionMode: RouteRegionMode, orderMode: RouteOrderMode,
        maxExpansions: Int = MAX_EXPANSIONS, maxHops: Int = MAX_HOPS,
        originDistance: PlatformDistance? = null, reviewedOrigin: ReviewedOriginAccess? = null): RoutePlanResult {
        fun blocked(issue: RoutePlanProblem) = RoutePlanResult.Blocked(issue)
        if (maxExpansions !in 1..MAX_EXPANSIONS || maxHops !in 1..MAX_HOPS) return blocked(RoutePlanProblem.SEARCH_LIMIT)
        val pointScope = selected.scope
        val scope = graph.scope
        if (pointScope != runCatching { policy.scope(pointScope.connectionEpoch) }.getOrNull() ||
            pointScope.connectionEpoch != scope.connectionEpoch || pointScope.schoolId != scope.schoolId ||
            pointScope.termId != scope.termId || pointScope.zoneId != scope.zoneId || selected.selectedOn != day ||
            !graph.fingerprint.matches(Regex("[a-f0-9]{64}"))) return blocked(RoutePlanProblem.CONTEXT_CHANGED)
        val selectedIds = selected.selectedIds
        if (selectedIds.size != policy.count || selectedIds.size !in 2..8 || selectedIds.distinct().size != selectedIds.size ||
            selectedIds.any { id -> policy.candidates.none { it.id == id } || graph.nodes[id] == null } ||
            selected.confirmedIds.any { it !in selectedIds }) return blocked(RoutePlanProblem.INVALID_POINTS)
        if (selected.locked || selected.confirmedIds.isNotEmpty()) return blocked(RoutePlanProblem.ACTIVE_PLAN_REUSE_REQUIRED)
        val latestPolicy = runCatching { PointPolicyFactory.create(config, secondOfDay, policy.distanceImplementationId) }.getOrNull()
        val latestScope = runCatching {
            CandidateRoadRequest.create(config, scope.connectionEpoch, selectedIds[0], selectedIds[1]).scope
        }.getOrNull()
        if (latestPolicy?.fingerprint != policy.fingerprint || latestScope != scope ||
            ConfigurationReviewer.review(config).candidates?.associate { it.id to it.coordinate } != graph.nodes)
            return blocked(RoutePlanProblem.CONTEXT_CHANGED)
        if (regionMode == RouteRegionMode.UNKNOWN) return blocked(RoutePlanProblem.REGION_RULE_UNKNOWN)
        if (orderMode == RouteOrderMode.UNKNOWN) return blocked(RoutePlanProblem.ORDER_RULE_UNKNOWN)
        val review = ConfigurationReviewer.review(config)
        if (review.forbiddenRegionIsEmpty != true) return blocked(RoutePlanProblem.FORBIDDEN_REGION_UNKNOWN)
        val polygon = if (regionMode == RouteRegionMode.REQUIRE_BOUNDARY)
            review.allowedBoundary?.let(CampusGeometry::prepare) ?: return blocked(RoutePlanProblem.AREA_INVALID) else null
        if (polygon != null && selectedIds.any { !polygon.segmentInside(graph.nodes.getValue(it), graph.nodes.getValue(it)) })
            return blocked(RoutePlanProblem.CHECKPOINT_OUTSIDE)
        val minMeters = policy.rule.minimumKm * 1000
        val maxMeters = policy.rule.maximumKm * 1000
        if (!minMeters.isFinite() || !maxMeters.isFinite() || minMeters <= 0 || maxMeters > 1_000_000 || maxMeters <= minMeters)
            return blocked(RoutePlanProblem.NO_DISTANCE_MARGIN)
        val goalMm = ceil((minMeters + maxOf(10.0, minMeters * 0.01)) * 1000).toLong()
        val limitMm = floor((maxMeters - maxOf(10.0, maxMeters * 0.005)) * 1000).toLong()
        if (goalMm > limitMm) return blocked(RoutePlanProblem.NO_DISTANCE_MARGIN)
        val normalEdges = graph.edges.filter { edge ->
            val exact = edge.points.firstOrNull() == graph.nodes[edge.fromId] &&
                edge.points.lastOrNull() == graph.nodes[edge.toId]
            val nearby = ClientCheckpointRadius.meters(config) != null &&
                graph.endpointProximity?.get(edge.fromId to edge.toId)?.let {
                    it.startWithinRadius && it.endWithinRadius
                } == true
            edge.points.size >= 2 && (exact || nearby) && edge.meters.isFinite() && edge.meters >= 0.001 &&
                (polygon == null || edge.points.zipWithNext().all { (a, b) -> polygon.segmentInside(a, b) })
        }
        // Start-only suffixes are derived from accepted forward roads; no GPS-to-road connector is invented.
        val origin = selected.origin.coordinate
        val virtualStart = startId == ObservedOriginAccess.START_ID
        val startEdges = if (virtualStart && reviewedOrigin != null) {
            (reviewedOrigin.edges(graph, origin, normalEdges) ?: return blocked(RoutePlanProblem.CONTEXT_CHANGED))
                .filter { edge -> polygon == null || edge.points.zipWithNext().all { (a,b) -> polygon.segmentInside(a,b) } }
        } else if (virtualStart && originDistance != null) {
            val safeGraph = ObservedRoadGraph(graph.scope, graph.fingerprint, graph.nodes, normalEdges,
                graph.unjoinedLegs, graph.offsetLegs)
            ObservedOriginAccess.find(safeGraph, origin, originDistance).orEmpty()
        } else emptyList()
        val allowed = (normalEdges + startEdges).groupBy { it.fromId }
        // GPS is never snapped to a road. Even two road ends near the same checkpoint cannot teleport.
        if ((!virtualStart && graph.nodes[startId] == null) || (graph.nodes[startId] != origin &&
                allowed[startId].orEmpty().none { it.points.first() == origin }))
            return blocked(RoutePlanProblem.START_NOT_ON_ROAD)
        fun advance(mask: Int, node: String): Int? {
            val idx = selectedIds.indexOf(node)
            if (idx < 0 || mask and (1 shl idx) != 0) return mask
            if (orderMode == RouteOrderMode.ORDERED && idx != Integer.bitCount(mask)) return null
            return mask or (1 shl idx)
        }
        val initial = advance(0, startId) ?: return blocked(RoutePlanProblem.NO_OBSERVED_ROUTE)
        val start = Label(Key(startId, origin, initial, 0, 0), 0, null, null)
        val queue = PriorityQueue<Label>(compareBy<Label> { it.key.lowerMm }.thenBy { it.key.upperMm }.thenBy { it.hops })
        val seen = mutableMapOf(start.key to 0)
        queue.add(start)
        val all = (1 shl selectedIds.size) - 1
        var expansions = 0
        var truncated = false
        val finish = originDistance?.let(::RoadFinishPrefix)
        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (seen[current.key] != current.hops) continue
            if (++expansions > maxExpansions) return blocked(RoutePlanProblem.SEARCH_LIMIT)
            if (current.key.visited == all && current.key.lowerMm >= goalMm && current.key.upperMm <= limitMm) {
                if (truncated) return blocked(RoutePlanProblem.SEARCH_LIMIT)
                val legs = generateSequence(current) { it.parent }.mapNotNull { it.edge }.toList().asReversed()
                val visits = mutableListOf<String>()
                (listOf(startId) + legs.map { it.toId }).forEach { if (it in selectedIds && it !in visits) visits.add(it) }
                return RoutePlanResult.Draft(FixedPointRoute(scope, graph.fingerprint, selectedIds.toList(), startId,
                    visits, legs, current.key.lowerMm / 1000.0, current.key.upperMm / 1000.0,
                    goalMm / 1000.0, limitMm / 1000.0, graph.manualConfirmationCount > 0, graph.reviewedTwoWayRoadCount > 0, reviewedOrigin != null,
                    reviewedOrigin?.proposal?.gapMeters ?: 0.0,reviewedOrigin?.proposal?.source))
            }
            if (current.hops >= maxHops) { if (!allowed[current.key.node].isNullOrEmpty()) truncated = true; continue }
            val outgoing = allowed[current.key.node].orEmpty().filter { it.points.first() == current.key.position }
            val finishing = if (current.key.visited == all && current.key.lowerMm < goalMm && finish != null) {
                outgoing.mapNotNull { edge -> finish.take(edge, (goalMm - current.key.lowerMm) / 1000.0 + 0.002) }
            } else emptyList()
            if (finish?.limited == true) truncated = true
            for (edge in outgoing + finishing) {
                val nextMask = advance(current.key.visited, edge.toId) ?: continue
                val lower = current.key.lowerMm + floor(edge.meters * 1000).toLong()
                val upper = current.key.upperMm + ceil(edge.meters * 1000).toLong()
                if (upper > limitMm) continue
                val key = Key(edge.toId, edge.points.last(), nextMask, lower, upper)
                val hops = current.hops + 1
                if ((seen[key] ?: Int.MAX_VALUE) <= hops) continue
                if (seen.size >= maxExpansions * 4) return blocked(RoutePlanProblem.SEARCH_LIMIT)
                seen[key] = hops
                queue.add(Label(key, hops, current, edge))
            }
        }
        return blocked(if (truncated) RoutePlanProblem.SEARCH_LIMIT else RoutePlanProblem.NO_OBSERVED_ROUTE)
    }
}

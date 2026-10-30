package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.domain.PointPolicyFactory
import io.github.ibudaoa.domain.PointSelectionCache
import io.github.ibudaoa.domain.PlatformDistance
import java.time.Instant
import java.time.ZoneId

/** Uses an existing assigned point set only. No drawing, allocation, location collection or platform writes. */
object RouteDraftBuilder {
    fun build(config: DiagnosticConfig, captured: Boolean, selected: PointSelectionCache,
        graph: ObservedRoadGraph, distanceImplementationId: String, now: Instant, campusZone: ZoneId,
        originDistance: PlatformDistance? = null, reviewedOrigin: ReviewedOriginAccess? = null): RoutePlanResult {
        if (!captured) return RoutePlanResult.Blocked(RoutePlanProblem.CONTEXT_CHANGED)
        val local = now.atZone(campusZone)
        val second = local.toLocalTime().toSecondOfDay()
        val policy = runCatching { PointPolicyFactory.create(config, second, distanceImplementationId) }.getOrNull()
            ?: return RoutePlanResult.Blocked(RoutePlanProblem.CONTEXT_CHANGED)
        // A real origin can join only at an exact observed segment; nearby GPS is not silently snapped.
        val start = if (reviewedOrigin != null) ObservedOriginAccess.START_ID else graph.nodes.entries.firstOrNull { it.value == selected.origin.coordinate }?.key
            ?: graph.edges.firstOrNull { it.points.first() == selected.origin.coordinate }?.fromId
            ?: ObservedOriginAccess.START_ID
        return FixedPointRoutePlanner.plan(config, policy, selected, local.toLocalDate(), second, graph, start,
            ClientRegionEvidence.forCurrentClient(config), ClientOrderEvidence.forCurrentClient(config), originDistance = originDistance, reviewedOrigin = reviewedOrigin)
    }
}

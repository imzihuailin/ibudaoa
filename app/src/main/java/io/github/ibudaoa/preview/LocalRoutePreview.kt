package io.github.ibudaoa.preview

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.diagnostics.StageVerification
import io.github.ibudaoa.domain.*
import io.github.ibudaoa.maps.DisplayFix
import io.github.ibudaoa.maps.ForegroundFixGate
import io.github.ibudaoa.roads.*
import java.time.Instant
import java.time.ZoneId

enum class PreviewProblem(val label: String) {
    LOCATION_REQUIRED("请等待新鲜、准确的前台定位"), CONTEXT_CHANGED("配置或道路已变化，请重新预览"),
    RULES_UNAVAILABLE("当前规则尚未就绪或不在适用时段"), FACE_UNSUPPORTED("当前核验要求尚未适配"),
    POINT_SELECTION("当前无法按规则拟定点位"), ORIGIN_ACCESS_UNAVAILABLE("当前位置暂不能接入已有道路，请检查位置与道路距离"),
    POSITION_CHANGED("位置已变化，请重新核对起点连接"), ROUTE_UNAVAILABLE("当前道路尚不能生成满足要求的完整草稿"),
}
enum class PreviewPhase { REVIEW_ORIGIN, DRAFT, BLOCKED }

/** Private process memory only. Neither a platform assignment nor a running session. */
data class LocalRoutePreview internal constructor(
    val proposal: OriginRoadProposal,
    val points: PointSelectionCache,
    val graphIdentity: String,
    val route: FixedPointRoute? = null,
    val routeProblem: RoutePlanProblem? = null,
)

data class LocalPreviewSummary(
    val scope: RoadScope, val phase: PreviewPhase, val selectedCount: Int = 0,
    val originReviewed: Boolean = false, val originGapMeters: Double? = null,
    val routeMeters: Double? = null, val problem: PreviewProblem? = null,
    val pointProblem: PointSelectionProblem? = null, val routeProblem: RoutePlanProblem? = null,
)
sealed interface PreviewPreparation {
    data class Ready(val preview: LocalRoutePreview) : PreviewPreparation
    data class Blocked(val problem: PreviewProblem, val pointProblem: PointSelectionProblem? = null) : PreviewPreparation
}

object LocalRoutePreviewEngine {
    fun fresh(fix: DisplayFix?, elapsed: Long): Boolean = fix != null && elapsed >= 0 && fix.receivedElapsedMillis >= 0 && elapsed >= fix.receivedElapsedMillis &&
        elapsed-fix.receivedElapsedMillis <= ForegroundFixGate.MAX_AGE_MS && fix.accuracyMeters.isFinite() &&
        fix.accuracyMeters > 0 && fix.accuracyMeters <= 50 && fix.coordinate.latitude.isFinite() &&
        fix.coordinate.longitude.isFinite() && fix.coordinate.latitude in -90.0..90.0 && fix.coordinate.longitude in -180.0..180.0

    fun prepare(config: DiagnosticConfig, captured: Boolean, graph: ObservedRoadGraph, fix: DisplayFix?,
        cached: PointSelectionCache?, now: Instant, elapsed: Long, zone: ZoneId, distance: PlatformDistance,
        access:OriginRoadProposal?=null): PreviewPreparation {
        if (!captured) return PreviewPreparation.Blocked(PreviewProblem.CONTEXT_CHANGED)
        if (!fresh(fix,elapsed)) return PreviewPreparation.Blocked(PreviewProblem.LOCATION_REQUIRED)
        if (config.stageVerification !in setOf(StageVerification.SKIPPED_EMPTY, StageVerification.DISABLED))
            return PreviewPreparation.Blocked(PreviewProblem.FACE_UNSUPPORTED)
        val calendar = CalendarReviewer.review(config,now,zone)
        if (calendar.termPosition != TermPosition.WITHIN || calendar.currentTimeRule != CurrentTimeRule.UNIQUE)
            return PreviewPreparation.Blocked(PreviewProblem.RULES_UNAVAILABLE)
        val policy = try { PointPolicyFactory.create(config,now.atZone(zone).toLocalTime().toSecondOfDay(),distance.implementationId) }
            catch (e: PointSelectionException) { return PreviewPreparation.Blocked(PreviewProblem.POINT_SELECTION,e.problem) }
        val scope = runCatching { CandidateRoadRequest.create(config,graph.scope.connectionEpoch,
            policy.candidates[0].id,policy.candidates[1].id).scope }.getOrNull()
        if (scope != graph.scope) return PreviewPreparation.Blocked(PreviewProblem.CONTEXT_CHANGED)
        val proposal = access ?: OriginRoadProposalBuilder.create(graph,fix!!.coordinate,distance)
            ?: return PreviewPreparation.Blocked(PreviewProblem.ORIGIN_ACCESS_UNAVAILABLE)
        if(proposal.scope!=graph.scope || proposal.graphIdentity!=GraphGeometryIdentity.of(graph)) return PreviewPreparation.Blocked(PreviewProblem.CONTEXT_CHANGED)
        val shift=runCatching { distance.meters(proposal.origin,fix!!.coordinate) }.getOrNull()
        if(shift==null || !shift.isFinite() || shift>5.0) return PreviewPreparation.Blocked(PreviewProblem.POSITION_CHANGED)
        val result = PointSelectionEngine(distance).resolve(policy,graph.scope.connectionEpoch,now.atZone(zone).toLocalDate(),
            PlatformOrigin(fix!!.coordinate),cached)
        val points = when (result) {
            is PointResolution.Selected -> result.cache
            is PointResolution.Reused -> result.cache
            is PointResolution.Blocked -> return PreviewPreparation.Blocked(PreviewProblem.POINT_SELECTION,result.problem)
        }
        return PreviewPreparation.Ready(LocalRoutePreview(proposal,points,GraphGeometryIdentity.of(graph)))
    }

    fun reviewProblem(config: DiagnosticConfig, captured: Boolean, graph: ObservedRoadGraph,
        preview: LocalRoutePreview, fix: DisplayFix?, now: Instant, elapsed: Long, zone: ZoneId,
        distance: PlatformDistance): PreviewProblem? {
        if (!captured || distance.implementationId != WalkingRoadReviewer.DISTANCE) return PreviewProblem.CONTEXT_CHANGED
        if (!fresh(fix,elapsed)) return PreviewProblem.LOCATION_REQUIRED
        if (preview.proposal.scope != graph.scope || preview.graphIdentity != GraphGeometryIdentity.of(graph))
            return PreviewProblem.CONTEXT_CHANGED
        val movement=runCatching { distance.meters(preview.proposal.origin,fix!!.coordinate) }.getOrNull()
        if (movement == null || !movement.isFinite() || movement < 0 || movement > 5.0) return PreviewProblem.POSITION_CHANGED
        val calendar=CalendarReviewer.review(config,now,zone)
        if (calendar.termPosition != TermPosition.WITHIN || calendar.currentTimeRule != CurrentTimeRule.UNIQUE)
            return PreviewProblem.RULES_UNAVAILABLE
        val policy=runCatching { PointPolicyFactory.create(config,now.atZone(zone).toLocalTime().toSecondOfDay(),distance.implementationId) }.getOrNull()
        if (policy == null || policy.scope(graph.scope.connectionEpoch) != preview.points.scope ||
            preview.points.selectedOn != now.atZone(zone).toLocalDate()) return PreviewProblem.RULES_UNAVAILABLE
        return null
    }

    fun validateAndBuild(config: DiagnosticConfig, captured: Boolean, graph: ObservedRoadGraph, preview: LocalRoutePreview,
        fix: DisplayFix?, now: Instant, elapsed: Long, zone: ZoneId, distance: PlatformDistance): Pair<LocalRoutePreview?, LocalPreviewSummary> {
        fun blocked(p: PreviewProblem) = null to LocalPreviewSummary(graph.scope,PreviewPhase.BLOCKED,
            preview.points.selectedIds.size,problem=p)
        reviewProblem(config,captured,graph,preview,fix,now,elapsed,zone,distance)?.let { return blocked(it) }
        // Preserve the selection's original refresh anchor; use the reviewed position only for this route calculation.
        val result = RouteDraftBuilder.build(config,captured,preview.points.copy(origin=PlatformOrigin(preview.proposal.origin)),
            graph,distance.implementationId,now,zone,distance,ReviewedOriginAccess.approve(preview.proposal))
        val route = (result as? RoutePlanResult.Draft)?.route
        val issue = (result as? RoutePlanResult.Blocked)?.problem
        val updated = preview.copy(route=route,routeProblem=issue)
        return updated to LocalPreviewSummary(graph.scope,if (route != null) PreviewPhase.DRAFT else PreviewPhase.BLOCKED,
            preview.points.selectedIds.size,true,preview.proposal.gapMeters,route?.upperDistanceMeters,
            if (route == null) PreviewProblem.ROUTE_UNAVAILABLE else null,routeProblem=issue)
    }

    // Compatibility alias for existing offline tests and historical callers.
    fun confirm(config: DiagnosticConfig, captured: Boolean, graph: ObservedRoadGraph, preview: LocalRoutePreview,
        fix: DisplayFix?, now: Instant, elapsed: Long, zone: ZoneId, distance: PlatformDistance) =
        validateAndBuild(config, captured, graph, preview, fix, now, elapsed, zone, distance)
}

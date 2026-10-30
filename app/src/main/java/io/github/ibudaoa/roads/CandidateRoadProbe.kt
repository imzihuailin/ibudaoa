package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.DiagnosticCodec
import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.domain.ConfigurationReviewer
import io.github.ibudaoa.domain.PlatformDistance
import java.security.MessageDigest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One whole configuration, not just a zone id. Contains no account/device credentials. */
data class RoadScope(val connectionEpoch: Long, val schoolId: Long, val termId: Long, val zoneId: Long, val configHash: String)

class CandidateRoadRequest private constructor(
    val scope: RoadScope, val fromId: String, val toId: String,
    val from: CampusCoordinate, val to: CampusCoordinate,
) {
    companion object {
        fun create(config: DiagnosticConfig, connectionEpoch: Long, fromId: String, toId: String): CandidateRoadRequest {
            require(connectionEpoch > 0 && fromId != toId)
            fun positive(key: String) = (config.config[key] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("Incomplete road configuration scope")
            val candidates = ConfigurationReviewer.review(config).candidates
                ?: throw IllegalArgumentException("Invalid candidate table")
            val from = candidates.singleOrNull { it.id == fromId } ?: throw IllegalArgumentException("Unknown road endpoint")
            val to = candidates.singleOrNull { it.id == toId } ?: throw IllegalArgumentException("Unknown road endpoint")
            val hash = MessageDigest.getInstance("SHA-256").digest(DiagnosticCodec.encode(config).toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            return CandidateRoadRequest(RoadScope(connectionEpoch, positive("school_id"), positive("term_id"),
                positive("run_zone_id"), hash), fromId, toId, from.coordinate, to.coordinate)
        }
    }
}

/** SDK road geometry only, never invented connectors or local GPS. No road names or raw responses. */
data class WalkingLeg(val distanceMeters: Double, val steps: List<List<CampusCoordinate>>)
data class WalkingReply(val request: CandidateRoadRequest, val sourceVersion: String, val paths: List<WalkingLeg>)

enum class RoadGeometryIssue {
    STALE_CONTEXT, MISMATCHED_QUERY, INVALID_SOURCE, EMPTY_PATH, INVALID_DISTANCE,
    INVALID_COORDINATE, MISSING_GEOMETRY, DISCONNECTED_STEPS, DISTANCE_INCONSISTENT, DISTANCE_UNAVAILABLE,
}

data class WalkingLegReview(
    val issues: Set<RoadGeometryIssue>, val geometryMeters: Double?,
    val startOffsetMeters: Double?, val endOffsetMeters: Double?,
    val leavesConfiguredBoundary: Boolean?, val forbiddenRegionUnknown: Boolean,
    val maximumStepGapMeters: Double? = null,
)

/** Integrity diagnostics only: valid SDK geometry does not prove walkability or platform eligibility. */
object WalkingRoadReviewer {
    fun review(expected: CandidateRoadRequest, reply: WalkingReply, leg: WalkingLeg,
        config: DiagnosticConfig, distance: PlatformDistance): WalkingLegReview {
        val issues = linkedSetOf<RoadGeometryIssue>()
        if (reply.request.scope != expected.scope) issues += RoadGeometryIssue.STALE_CONTEXT
        if (reply.request.fromId != expected.fromId || reply.request.toId != expected.toId ||
            reply.request.from != expected.from || reply.request.to != expected.to) issues += RoadGeometryIssue.MISMATCHED_QUERY
        val current = runCatching {
            CandidateRoadRequest.create(config, expected.scope.connectionEpoch, expected.fromId, expected.toId)
        }.getOrNull()
        if (current?.scope != expected.scope) issues += RoadGeometryIssue.STALE_CONTEXT
        if (reply.sourceVersion != SOURCE || distance.implementationId != DISTANCE) issues += RoadGeometryIssue.INVALID_SOURCE
        if (reply.paths.none { it === leg }) issues += RoadGeometryIssue.MISMATCHED_QUERY
        if (reply.paths.isEmpty()) issues += RoadGeometryIssue.EMPTY_PATH
        if (!leg.distanceMeters.isFinite() || leg.distanceMeters <= 0) issues += RoadGeometryIssue.INVALID_DISTANCE
        if (leg.steps.isEmpty() || leg.steps.any { it.size < 2 }) issues += RoadGeometryIssue.MISSING_GEOMETRY
        val points = leg.steps.flatten()
        if (points.any { !it.valid() }) issues += RoadGeometryIssue.INVALID_COORDINATE
        if (leg.steps.zipWithNext().any { (a, b) -> a.lastOrNull() != b.firstOrNull() })
            issues += RoadGeometryIssue.DISCONNECTED_STEPS
        var maximumGap: Double? = null
        if (issues.all { it == RoadGeometryIssue.DISCONNECTED_STEPS }) {
            try {
                maximumGap = leg.steps.zipWithNext().maxOfOrNull { (a, b) ->
                    distance.meters(a.last(), b.first()).also { check(it.isFinite() && it >= 0) }
                } ?: 0.0
            } catch (_: Exception) { issues += RoadGeometryIssue.DISTANCE_UNAVAILABLE }
        }
        // Quarantine stale/wrong-source geometry as a whole; never return coordinates or measurements for it.
        val hasEndpointMismatch = RoadGeometryIssue.DISCONNECTED_STEPS in issues
        // A step boundary mismatch blocks continuity, but does not invalidate the observed steps.
        // Measure each step separately; never add or draw a connector across the unknown interval.
        val geometryValid = issues.all { it == RoadGeometryIssue.DISCONNECTED_STEPS }
        var meters: Double? = null
        var startOffset: Double? = null
        var endOffset: Double? = null
        if (geometryValid) {
            try {
                fun measured(a: CampusCoordinate, b: CampusCoordinate) = distance.meters(a, b).also {
                    check(it.isFinite() && it >= 0)
                }
                meters = leg.steps.sumOf { step -> step.zipWithNext().sumOf { (a, b) -> measured(a, b) } }
                startOffset = measured(expected.from, points.first())
                endOffset = measured(points.last(), expected.to)
                // SDK total is rounded; this is an integrity tolerance, not a running distance margin.
                if (meters <= 0 || kotlin.math.abs(meters - leg.distanceMeters) > maxOf(10.0, leg.distanceMeters * 0.10))
                    issues += RoadGeometryIssue.DISTANCE_INCONSISTENT
            } catch (_: Exception) {
                issues += RoadGeometryIssue.DISTANCE_UNAVAILABLE
                meters = null; startOffset = null; endOffset = null
            }
        }
        val review = ConfigurationReviewer.review(config)
        val polygon = review.allowedBoundary?.let(CampusGeometry::prepare)
        val observedOutside = if (geometryValid && polygon != null)
            leg.steps.any { step -> step.zipWithNext().any { (a, b) ->
                !polygon.segmentInside(a, b)
            } } else null
        // Any observed outside segment is evidence of exit. An unobserved gap cannot prove containment.
        val leaves = when {
            observedOutside == true -> true
            observedOutside == false && !hasEndpointMismatch -> false
            else -> null
        }
        return WalkingLegReview(issues, meters, startOffset, endOffset, leaves,
            review.forbiddenRegionIsEmpty != true, maximumGap)
    }

    const val SOURCE = "amap-walk-v2-map11.3.100-search9.8.1"
    const val DISTANCE = "amap-11.3.100-calculateLineDistance-v1"
    private fun CampusCoordinate.valid() = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
}

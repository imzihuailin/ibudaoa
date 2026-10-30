package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.domain.ConfigurationReviewer
import io.github.ibudaoa.domain.PlatformDistance
import java.security.MessageDigest

/** Endpoints are derived solely from current cached candidate roads, never from device location. */
data class RoadGap internal constructor(val from: CampusCoordinate, val to: CampusCoordinate)
data class RoadGapPlan internal constructor(val scope: RoadScope, val evidenceFingerprint: String,
    val gaps: List<RoadGap>, val eligibleGapCount: Int) {
    companion object {
        const val MAX_REQUESTS = 24
        private const val MAX_GAP_METERS = 30.0
        fun create(config: DiagnosticConfig, evidence: RoadEvidenceSnapshot, distance: PlatformDistance): RoadGapPlan {
            require(ConfigurationReviewer.review(config).forbiddenRegionIsEmpty == true)
            require(evidence.legs.isNotEmpty() && evidence.legs.size <= 48)
            val distinct = linkedSetOf<RoadGap>()
            for (record in evidence.legs) {
                val request = CandidateRoadRequest.create(config, evidence.scope.connectionEpoch, record.fromId, record.toId)
                require(request.scope == evidence.scope)
                val reply = WalkingReply(request, WalkingRoadReviewer.SOURCE, listOf(record.leg))
                val review = WalkingRoadReviewer.review(request, reply, record.leg, config, distance)
                if (review.issues.any { it != RoadGeometryIssue.DISCONNECTED_STEPS }) continue
                for ((before, after) in record.leg.steps.zipWithNext()) {
                    if (before.last() == after.first() || ObservedRoadAssembly.assemble(listOf(before, after)).continuous) continue
                    val meters = distance.meters(before.last(), after.first())
                    if (meters.isFinite() && meters > 0 && meters <= MAX_GAP_METERS)
                        distinct.add(RoadGap(before.last(), after.first()))
                }
            }
            return RoadGapPlan(evidence.scope, fingerprint(evidence), distinct.take(MAX_REQUESTS), distinct.size)
        }
        fun fingerprint(evidence: RoadEvidenceSnapshot): String =
            MessageDigest.getInstance("SHA-256").digest(RoadEvidenceCodec.encode(evidence).toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

enum class GapCheckOutcome { EXACT_CONNECTED, OFFSET_OR_GAP, INVALID_GEOMETRY, NO_ROUTE, TIMEOUT, SERVICE_ERROR }

/** Exact SDK endpoints and observed continuity are required; never interpolate a missing segment. */
object RoadGapReviewer {
    fun review(gap: RoadGap, paths: List<WalkingLeg>): GapCheckOutcome {
        val leg = paths.firstOrNull() ?: return GapCheckOutcome.NO_ROUTE
        if (!leg.distanceMeters.isFinite() || leg.distanceMeters <= 0 || leg.steps.isEmpty() ||
            leg.steps.sumOf { it.size.toLong() } > 20_000 || leg.steps.any { it.size < 2 })
            return GapCheckOutcome.INVALID_GEOMETRY
        val assembled = runCatching { ObservedRoadAssembly.assemble(leg.steps) }.getOrNull()
            ?: return GapCheckOutcome.INVALID_GEOMETRY
        if (!assembled.continuous) return GapCheckOutcome.OFFSET_OR_GAP
        val points = assembled.components.single()
        return if (points.first() == gap.from && points.last() == gap.to) GapCheckOutcome.EXACT_CONNECTED
            else GapCheckOutcome.OFFSET_OR_GAP
    }
}

data class RoadGapReport(val scope: RoadScope, val evidenceFingerprint: String, val eligibleGapCount: Int,
    val planned: Int, val outcomes: List<GapCheckOutcome>, val interrupted: Boolean)

data class RoadGapRecord(val gap: RoadGap, val outcome: GapCheckOutcome, val bridge: WalkingLeg? = null)
data class RoadGapEvidence(val scope: RoadScope, val evidenceFingerprint: String, val records: List<RoadGapRecord>)

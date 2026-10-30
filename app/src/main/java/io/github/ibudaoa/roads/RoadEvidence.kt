package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.domain.ConfigurationReviewer
import io.github.ibudaoa.domain.PlatformDistance
import java.security.MessageDigest

/** Public campus-candidate geometry only. User origins must use a separate future private session model. */
data class CandidateRoadEvidence(val fromId: String, val toId: String, val leg: WalkingLeg)
data class RoadEvidenceSnapshot(val scope: RoadScope, val legs: List<CandidateRoadEvidence>)

/** IDs refer to official checkpoints; points retain actual road anchors, which need not be their centres. */
data class ObservedRoadEdge internal constructor(
    val fromId: String, val toId: String, val points: List<io.github.ibudaoa.domain.CampusCoordinate>,
    val meters: Double,
)
/** Client-side endpoint proximity only; this says nothing about continuity or actual check-ins. */
data class CheckpointEndpointProximity(val startWithinRadius: Boolean, val endWithinRadius: Boolean)
class ObservedRoadGraph internal constructor(
    val scope: RoadScope, val fingerprint: String,
    val nodes: Map<String, io.github.ibudaoa.domain.CampusCoordinate>,
    val edges: List<ObservedRoadEdge>,
    val unjoinedLegs: Set<Pair<String, String>>, val offsetLegs: Set<Pair<String, String>>,
    val endpointProximity: Map<Pair<String, String>, CheckpointEndpointProximity>? = null,
    val topology: RoadTopologySummary? = null,
    val verifiedGapBridges: Int = 0,
    val locallyResolvedGaps: Int = 0,
    val localResolutionLimited: Boolean = false,
    val manualConfirmationCount: Int = 0,
    val reviewedTwoWayRoadCount: Int = 0,
)

object ObservedRoadGraphBuilder {
    fun build(config: DiagnosticConfig, snapshot: RoadEvidenceSnapshot, distance: PlatformDistance,
        gapEvidence: RoadGapEvidence? = null, manualGaps: ManualRoadGaps? = null): ObservedRoadGraph {
        require(snapshot.legs.isNotEmpty() && snapshot.legs.size <= 48)
        val nodes = ConfigurationReviewer.review(config).candidates?.associate { it.id to it.coordinate }
            ?: error("Invalid graph candidate table")
        val edges = mutableListOf<ObservedRoadEdge>()
        val unjoined = linkedSetOf<Pair<String, String>>()
        val offset = linkedSetOf<Pair<String, String>>()
        val radius = ClientCheckpointRadius.meters(config)
        val proximity = mutableMapOf<Pair<String, String>, CheckpointEndpointProximity>()
        val accepted = snapshot.legs.filter { record ->
            val request = CandidateRoadRequest.create(config, snapshot.scope.connectionEpoch, record.fromId, record.toId)
            require(request.scope == snapshot.scope)
            val reply = WalkingReply(request, WalkingRoadReviewer.SOURCE, listOf(record.leg))
            val review = WalkingRoadReviewer.review(request, reply, record.leg, config, distance)
            if (review.issues.any { it != RoadGeometryIssue.DISCONNECTED_STEPS }) return@filter false
            val startOffset = review.startOffsetMeters
            val endOffset = review.endOffsetMeters
            if (radius != null && startOffset != null && endOffset != null) {
                proximity[record.fromId to record.toId] = CheckpointEndpointProximity(startOffset <= radius, endOffset <= radius)
            }
            true
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(RoadEvidenceCodec.encode(snapshot).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val bridges = if (gapEvidence != null && gapEvidence.scope == snapshot.scope && gapEvidence.evidenceFingerprint == fingerprint &&
            runCatching { RoadGapEvidenceCodec.encode(gapEvidence) }.isSuccess) {
            val plan = runCatching { RoadGapPlan.create(config, snapshot, distance) }.getOrNull()
            if (plan == null || gapEvidence.records.any { it.gap !in plan.gaps }) emptyList()
            else gapEvidence.records.mapNotNull { it.bridge }
        } else emptyList()
        val manualPlan = if (manualGaps?.evidenceFingerprint == fingerprint)
            runCatching { RoadGapPlan.create(config, snapshot, distance) }.getOrNull() else null
        val manualBridges = if (manualPlan != null && manualGaps != null && manualGaps.indices.all { it in manualPlan.gaps.indices })
            manualGaps.indices.sorted().map { index ->
                val gap = manualPlan.gaps[index]
                WalkingLeg(distance.meters(gap.from, gap.to), listOf(listOf(gap.from, gap.to)))
            } else emptyList()
        val resolver = ObservedGapResolver(accepted.map { it.leg } + bridges + manualBridges)
        for (record in accepted) {
            val assembled = resolver.assemble(record.leg.steps)
            val pair = record.fromId to record.toId
            val hasOffset = assembled.components.first().first() != nodes.getValue(record.fromId) ||
                assembled.components.last().last() != nodes.getValue(record.toId)
            if (hasOffset) offset.add(pair)
            if (!assembled.continuous) unjoined.add(pair)
            // Keep the official checkpoint and road endpoint distinct. Proximity permits a local draft,
            // never a connector to the checkpoint, a successful check-in, or a jump between road ends.
            val nearby = proximity[pair]?.let { it.startWithinRadius && it.endWithinRadius } == true
            if (!assembled.continuous || (hasOffset && !nearby)) continue
            val line = assembled.components.single()
            val meters = line.zipWithNext().sumOf { (a, b) -> distance.meters(a, b).also { require(it.isFinite() && it >= 0) } }
            if (meters >= 0.001 && meters <= 1_000_000)
                edges.add(ObservedRoadEdge(record.fromId, record.toId, line, meters))
        }
        return ObservedRoadGraph(snapshot.scope, fingerprint, nodes.toMap(), edges.toList(), unjoined.toSet(), offset.toSet(),
            if (radius != null) proximity.toMap() else null,
            ObservedRoadTopology.inspect(accepted.map { it.leg } + bridges, nodes, radius, distance), bridges.size,
            resolver.resolved, resolver.budgetExhausted, manualBridges.size)
    }
}

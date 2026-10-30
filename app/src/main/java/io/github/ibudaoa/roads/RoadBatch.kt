package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.domain.ConfigurationReviewer
import io.github.ibudaoa.domain.PlatformDistance
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** Checking these links does not select the run's points or construct its full route. */
class RoadBatchPlan private constructor(val config: DiagnosticConfig, val requests: List<CandidateRoadRequest>) {
    val scope get() = requests.first().scope
    val candidateCount get() = requests.size + 1
    companion object {
        fun create(config: DiagnosticConfig, epoch: Long): RoadBatchPlan {
            val points = ConfigurationReviewer.review(config).candidates ?: error("Invalid candidates")
            require(points.size in 2..24)
            val ids = points.map { it.id }.sorted()
            // A fixed star covers all candidates with N-1 requests, including outside-boundary candidates.
            return RoadBatchPlan(config, ids.drop(1).map { CandidateRoadRequest.create(config, epoch, ids.first(), it) })
        }
    }
}

enum class RoadBatchPhase { IDLE, RUNNING, COMPLETE, INTERRUPTED, FAILED }
enum class RoadLinkFailure { NO_ROUTE, SDK_ERROR, AUTHORIZATION, QUOTA, NETWORK, TIMEOUT, INVALID_REPLY }
data class RoadLinkSummary(
    val fromId: String, val toId: String, val failure: RoadLinkFailure? = null,
    val issues: Set<RoadGeometryIssue> = emptySet(), val distanceMeters: Double? = null,
    val startOffsetMeters: Double? = null, val endOffsetMeters: Double? = null,
    val leavesBoundary: Boolean? = null, val forbiddenUnknown: Boolean = true,
    val maximumStepGapMeters: Double? = null,
)
data class RoadBatchReport(
    val scope: RoadScope, val phase: RoadBatchPhase, val candidateCount: Int,
    val plannedLinks: Int, val links: List<RoadLinkSummary> = emptyList(),
    val reviewRevision: Int = CURRENT_REVIEW_REVISION,
) {
    companion object { const val CURRENT_REVIEW_REVISION = 3 }
}
sealed interface RoadLinkOutcome {
    data class Received(val reply: WalkingReply) : RoadLinkOutcome
    data class Failed(val reason: RoadLinkFailure) : RoadLinkOutcome
}

object RoadBatchRunner {
    suspend fun run(plan: RoadBatchPlan, distance: PlatformDistance,
        probe: suspend (CandidateRoadRequest) -> RoadLinkOutcome,
        publish: (RoadBatchReport) -> Unit, paceMillis: Long = 1_000,
        observe: (CandidateRoadRequest, WalkingLeg) -> Unit = { _, _ -> }): RoadBatchReport {
        require(paceMillis >= 0)
        var report = RoadBatchReport(plan.scope, RoadBatchPhase.RUNNING, plan.candidateCount, plan.requests.size)
        publish(report)
        for ((index, request) in plan.requests.withIndex()) {
            if (index > 0) delay(paceMillis) // Avoid bursts; this is local pacing, not a claimed service quota.
            currentCoroutineContext().ensureActive()
            val outcome = try { probe(request) }
                catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    RoadLinkOutcome.Failed(RoadLinkFailure.TIMEOUT)
                }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { RoadLinkOutcome.Failed(RoadLinkFailure.SDK_ERROR) }
            var observed: WalkingLeg? = null
            val summary = when (outcome) {
                is RoadLinkOutcome.Failed -> RoadLinkSummary(request.fromId, request.toId, outcome.reason)
                is RoadLinkOutcome.Received -> {
                    val leg = outcome.reply.paths.firstOrNull()
                    if (leg == null) RoadLinkSummary(request.fromId, request.toId, RoadLinkFailure.INVALID_REPLY)
                    else {
                        val review = withContext(Dispatchers.Default) {
                            WalkingRoadReviewer.review(request, outcome.reply, leg, plan.config, distance)
                        }
                        if (review.issues.all { it in setOf(RoadGeometryIssue.DISCONNECTED_STEPS, RoadGeometryIssue.DISTANCE_INCONSISTENT) })
                            observed = leg
                        RoadLinkSummary(request.fromId, request.toId, issues = review.issues,
                            distanceMeters = review.geometryMeters, startOffsetMeters = review.startOffsetMeters,
                            endOffsetMeters = review.endOffsetMeters, leavesBoundary = review.leavesConfiguredBoundary,
                            forbiddenUnknown = review.forbiddenRegionUnknown, maximumStepGapMeters = review.maximumStepGapMeters)
                    }
                }
            }
            report = report.copy(links = report.links + summary)
            observed?.let { observe(request, it) }
            if (summary.failure in setOf(RoadLinkFailure.SDK_ERROR, RoadLinkFailure.AUTHORIZATION, RoadLinkFailure.QUOTA, RoadLinkFailure.NETWORK)) {
                return report.copy(phase = RoadBatchPhase.FAILED).also(publish)
            }
            publish(report)
        }
        return report.copy(phase = RoadBatchPhase.COMPLETE).also(publish)
    }
}

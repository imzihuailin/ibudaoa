package io.github.ibudaoa.domain

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.roads.*

/** Readiness for configuration only; never claims an executable route or valid run. */
object PreparationRoads {
    fun ready(config: DiagnosticConfig?, epoch: Long, report: RoadBatchReport?, evidence: RoadEvidenceSnapshot?): Boolean {
        if (config == null || report?.phase != RoadBatchPhase.COMPLETE ||
            report.reviewRevision != RoadBatchReport.CURRENT_REVIEW_REVISION || evidence == null) return false
        val plan = runCatching { RoadBatchPlan.create(config, epoch) }.getOrNull() ?: return false
        if (report.scope != plan.scope || evidence.scope != plan.scope || report.links.size != plan.requests.size ||
            report.links.any { it.failure != null }) return false
        val pairs = evidence.legs.filter { it.leg.steps.any { points -> points.size >= 2 } }
            .map { it.fromId to it.toId }.toSet()
        return plan.requests.all { (it.fromId to it.toId) in pairs }
    }
}

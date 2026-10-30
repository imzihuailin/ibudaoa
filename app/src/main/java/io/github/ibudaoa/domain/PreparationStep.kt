package io.github.ibudaoa.domain

import io.github.ibudaoa.roads.RoadBatchReport
import io.github.ibudaoa.roads.RoadBatchPhase

enum class PreparationStep { WAIT, COLLECT_CONFIG, RETURN_OFFICIAL, MAP_UNAVAILABLE, MAP_CONSENT, CHECK_ROADS, EXPORT_RESULT }

object PreparationStepResolver {
    fun next(captured: Boolean, captureRevision: Int, expectedRevision: Int, captureActive: Boolean,
        mapKey: Boolean, consent: Boolean, roadPhase: RoadBatchPhase?, busy: Boolean,
        roadReviewRevision: Int = RoadBatchReport.CURRENT_REVIEW_REVISION): PreparationStep = when {
        busy || roadPhase == RoadBatchPhase.RUNNING -> PreparationStep.WAIT
        captureActive -> PreparationStep.RETURN_OFFICIAL
        !captured || captureRevision != expectedRevision -> PreparationStep.COLLECT_CONFIG
        roadPhase in setOf(RoadBatchPhase.COMPLETE, RoadBatchPhase.FAILED) &&
            roadReviewRevision == RoadBatchReport.CURRENT_REVIEW_REVISION -> PreparationStep.EXPORT_RESULT
        !mapKey -> PreparationStep.MAP_UNAVAILABLE
        !consent -> PreparationStep.MAP_CONSENT
        else -> PreparationStep.CHECK_ROADS
    }
}

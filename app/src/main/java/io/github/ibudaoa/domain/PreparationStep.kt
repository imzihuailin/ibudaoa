package io.github.ibudaoa.domain

import io.github.ibudaoa.roads.RoadBatchReport
import io.github.ibudaoa.roads.RoadBatchPhase

enum class PreparationStep { WAIT, COLLECT_CONFIG, RETURN_OFFICIAL, MAP_UNAVAILABLE, ENABLE_LOCATION, CHECK_ROADS, RETRY, READY }

object PreparationStepResolver {
    fun next(captured: Boolean, captureRevision: Int, expectedRevision: Int, captureActive: Boolean,
        mapKey: Boolean, consent: Boolean, roadPhase: RoadBatchPhase?, busy: Boolean,
        roadReviewRevision: Int = RoadBatchReport.CURRENT_REVIEW_REVISION, roadsReady: Boolean = false, locationGranted: Boolean = true): PreparationStep = when {
        busy || roadPhase == RoadBatchPhase.RUNNING -> PreparationStep.WAIT
        captureActive -> PreparationStep.RETURN_OFFICIAL
        !captured || captureRevision != expectedRevision -> PreparationStep.COLLECT_CONFIG
        !mapKey -> PreparationStep.MAP_UNAVAILABLE
        !locationGranted || !consent -> PreparationStep.ENABLE_LOCATION
        roadsReady -> PreparationStep.READY
        roadReviewRevision != RoadBatchReport.CURRENT_REVIEW_REVISION -> PreparationStep.CHECK_ROADS
        roadPhase in setOf(RoadBatchPhase.COMPLETE, RoadBatchPhase.FAILED, RoadBatchPhase.INTERRUPTED) -> PreparationStep.RETRY
        else -> PreparationStep.CHECK_ROADS
    }
}

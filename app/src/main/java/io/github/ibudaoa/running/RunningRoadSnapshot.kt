package io.github.ibudaoa.running

import io.github.ibudaoa.data.PointPlanningStorage
import io.github.ibudaoa.diagnostics.DiagnosticCodec
import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.domain.PlatformDistance
import io.github.ibudaoa.roads.*

/** Builds from one committed storage revision, independently of UI flow collection. */
internal data class RunningRoadSnapshot(val storage: PointPlanningStorage, val base: ObservedRoadGraph,
    val graph: ObservedRoadGraph, val gaps: RoadGapPlan) {
    companion object {
        fun canonical(config: DiagnosticConfig) = DiagnosticCodec.decode(DiagnosticCodec.encode(config))
        fun build(storage: PointPlanningStorage, verified: DiagnosticConfig, distance: PlatformDistance): RunningRoadSnapshot {
            val config = storage.config ?: error("Missing committed configuration")
            check(storage.captured && config == canonical(verified))
            val report = storage.roadReport ?: error("Missing committed road report")
            val roads = storage.roadEvidence ?: error("Missing committed road evidence")
            check(report.scope == roads.scope && roads.scope.connectionEpoch == storage.connectionEpoch)
            check(roads.scope == RoadBatchPlan.create(config, storage.connectionEpoch).scope)
            val base = ObservedRoadGraphBuilder.build(config, roads, distance, storage.gapEvidence, storage.manualGaps)
            val graph = storage.reviewedDirections?.let {
                ReviewedRoadDirectionsBuilder.apply(base, it, storage.manualGaps)
            } ?: base
            return RunningRoadSnapshot(storage, base, graph, RoadGapPlan.create(config, roads, distance))
        }
    }
}

package io.github.ibudaoa.diagnostics

import io.github.ibudaoa.capture.CaptureState
import io.github.ibudaoa.capture.AccountReadReport
import io.github.ibudaoa.capture.AccountSessionSnapshot
import io.github.ibudaoa.capture.AccountSessionReport
import io.github.ibudaoa.running.RunningPlanSummary
import io.github.ibudaoa.BuildConfig
import io.github.ibudaoa.preview.LocalPreviewSummary
import io.github.ibudaoa.maps.LocationDiagnostics
import io.github.ibudaoa.roads.RoadBatchCodec
import io.github.ibudaoa.roads.RoadBatchPlan
import io.github.ibudaoa.roads.RoadBatchReport
import io.github.ibudaoa.roads.ObservedRoadGraph
import io.github.ibudaoa.roads.RoadGapShapeExport
import io.github.ibudaoa.roads.RoadEvidenceSnapshot
import io.github.ibudaoa.domain.PlatformDistance
import io.github.ibudaoa.roads.RoadGapReport
import io.github.ibudaoa.roads.RoadGapPlan
import io.github.ibudaoa.roads.GapCheckOutcome
import kotlinx.serialization.json.*

/** One reviewable export: existing allowlisted config plus a scoped, coordinate-free road summary. */
object PreparationExport {
    fun encode(config: DiagnosticConfig, epoch: Long, captureRevision: Int, report: RoadBatchReport?, capturedAtEpochSecond: Long = 0,
        graph: ObservedRoadGraph? = null, gapReport: RoadGapReport? = null,
        evidence: RoadEvidenceSnapshot? = null, distance: PlatformDistance? = null, preview: LocalPreviewSummary? = null, location: LocationDiagnostics? = null, accountCheck: CaptureState? = null,
        accountSession:AccountSessionSnapshot?=null,runningPlan:RunningPlanSummary?=null,
        sensors:io.github.ibudaoa.running.RunSensorSummary?=null,requirements:io.github.ibudaoa.running.RunRuntimeRequirements?=null,automatic:io.github.ibudaoa.running.AutomaticRunSummary?=null): String {
        require(captureRevision >= 0 && capturedAtEpochSecond >= 0)
        val base = Json.parseToJsonElement(DiagnosticCodec.encode(config)) as JsonObject
        val compatible = report?.takeIf { candidate -> runCatching {
            candidate.scope == RoadBatchPlan.create(config, epoch).scope
        }.getOrDefault(false) }
        val combined = JsonObject(base + buildMap {
            put("app_version",JsonPrimitive(BuildConfig.VERSION_NAME))
            put("app_version_code",JsonPrimitive(BuildConfig.VERSION_CODE))
            automatic?.let { auto -> put("automatic_execution",buildJsonObject {
                put("mode","GENERATED_ROUTE");put("phase",auto.phase.name)
                if(auto.meters.isFinite() && auto.meters in 0.0..1_000_000.0)put("meters",kotlin.math.floor(auto.meters).toInt())
                if(auto.targetMeters.isFinite() && auto.targetMeters in 0.0..1_000_000.0)put("target_meters",kotlin.math.ceil(auto.targetMeters).toInt())
                put("elapsed_seconds",auto.elapsedSeconds.coerceIn(0,1_000_000));put("visited_points",auto.visitedPoints.coerceIn(0,24))
                put("required_points",auto.requiredPoints.coerceIn(0,24));put("platform_started",auto.platformStarted)
                put("record_bound",auto.recordBound);put("record_verified",auto.recordVerified)
                auto.finishStage?.let { put("finish_stage",it.name) }
                if (auto.identityChecks.isNotEmpty()) put("identity_checks", buildJsonObject {
                    auto.identityChecks.filterKeys { it in setOf("id", "uid", "game_id", "start_time", "type") }.forEach { (key,value) -> put(key,value.name) }
                })
                auto.pendingWrite?.takeIf { value -> value in setOf("SELECT_ZONE","REGISTER","LOCATION","FINISH","GYROSCOPE") }?.let { put("pending_write",it) }
                auto.serverPointCount?.takeIf { it in 0..24 }?.let { put("server_checkins",it) }
                auto.serverStatus?.takeIf { it in -1..1000 }?.let { put("server_record_status",it) }
                auto.problem?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) }?.let { put("problem",it) }
            }) }
            sensors?.takeIf { it.steps in 0..100_000 && listOf(it.stepCallbacks,it.accelerationCallbacks,it.gyroscopeCallbacks,it.pairedSamples,it.rejected).all { n -> n in 0..1_000_000 } }?.let { check ->
                put("run_sensor_check",buildJsonObject {
                    put("phase",check.phase.name);put("completed",check.completed);put("sampled",check.sampled)
                    put("step_detector",check.stepDetector);put("step_counter",check.stepCounter)
                    put("accelerometer",check.accelerometer);put("gyroscope",check.gyroscope)
                    put("step_callbacks",check.stepCallbacks);put("observed_steps",check.steps)
                    put("acceleration_callbacks",check.accelerationCallbacks);put("gyroscope_callbacks",check.gyroscopeCallbacks)
                    put("paired_samples",check.pairedSamples);put("rejected",check.rejected)
                })
            }
            requirements?.let { check -> put("execution_requirements",buildJsonObject {
                put("run_checks_shape",check.runChecks.name);put("android_checks_shape",check.androidChecks.name)
                check.gyroscopeRequired?.let { put("gyroscope_required",it) }
                put("verification_supported",check.verificationSupported);put("supported",check.automaticSupported)
            }) }
            runningPlan?.let { plan -> put("running_plan",buildJsonObject {
                put("phase",plan.phase.name);plan.problem?.let { put("problem",it.name) }
                plan.failureStage?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) }?.let { put("failure_stage",it) }
                put("was_generated",plan.distanceMeters!=null && plan.paceSecondsPerKm!=null && plan.durationSeconds!=null && plan.pointCount!=null)
                plan.distanceMeters?.takeIf { it in 1..1_000_000 }?.let { put("distance_meters",it) }
                plan.paceSecondsPerKm?.takeIf { it in 1..100_000 }?.let { put("pace_seconds_per_km",it) }
                plan.durationSeconds?.takeIf { it in 1..1_000_000 }?.let { put("duration_seconds",it) }
                plan.pointCount?.takeIf { it in 1..24 }?.let { put("point_count",it) }
                plan.accessDistanceMeters?.takeIf { it in 0..1_000_000 }?.let { put("access_distance_meters",it) }
                put("platform_started",automatic?.platformStarted ?: false);put("record_verified",automatic?.recordVerified ?: false)
            }) }
            put("capture_revision", JsonPrimitive(captureRevision))
            if (capturedAtEpochSecond > 0) put("capture_device_epoch_second", JsonPrimitive(capturedAtEpochSecond))
            compatible?.let { put("preparation_check", RoadBatchCodec.encode(it)) }
            accountCheck?.let(AccountReadReport::summary)?.let { put("account_read_check",it) }
            accountSession?.let { put("account_session",AccountSessionReport.summary(it)) }
            location?.takeIf { it.callbacks in 0..1_000_000 && it.accepted in 0..it.callbacks &&
                it.sdkErrors in 0..it.callbacks && it.accepted+it.sdkErrors <= it.callbacks &&
                (it.lastSdkCode == null || it.lastSdkCode in 0..1000) }?.let { check ->
                put("foreground_location",buildJsonObject {
                    put("status",check.status.name);put("configured_transport","HTTPS")
                    put("fine_permission",check.finePermission)
                    check.systemLocationEnabled?.let { put("system_location_enabled",it) }
                    put("callbacks",check.callbacks);put("accepted",check.accepted);put("sdk_errors",check.sdkErrors);put("retries",check.retries)
                    check.lastSdkCode?.let { put("last_sdk_code",it) }
                    check.lastRejection?.let { put("last_rejection",it.name) }
                    put("accuracy_band",check.accuracyBand.name);put("coordinate_frame",check.coordinateFrame.name)
                })
            }
            if (compatible != null && graph?.scope == compatible.scope) {
                put("road_graph_summary", buildJsonObject {
                    put("continuous_directed_links", graph.edges.size)
                    put("unjoined_links", graph.unjoinedLegs.size)
                    put("offset_links", graph.offsetLegs.size)
                    put("verified_gap_bridges", graph.verifiedGapBridges)
                    put("user_confirmed_gaps", graph.manualConfirmationCount)
                    put("user_confirmed_two_way_roads", graph.reviewedTwoWayRoadCount)
                    put("locally_resolved_gaps", graph.locallyResolvedGaps)
                    put("local_resolution_limited", graph.localResolutionLimited)
                    put("offset_compatible_continuous_links", graph.edges.count { (it.fromId to it.toId) in graph.offsetLegs })
                    graph.endpointProximity?.let { proximity ->
                        put("checkpoint_proximity_checked_links", proximity.size)
                        put("checkpoint_proximity_both_endpoints_links", proximity.values.count {
                            it.startWithinRadius && it.endWithinRadius
                        })
                    }
                    graph.topology?.let { topology ->
                        put("observed_vertices", topology.vertices)
                        put("weak_components", topology.weakComponents)
                        topology.componentsCoveringAllCandidates?.let { put("components_near_all_candidates", it) }
                        topology.maximumCandidatesInOneComponent?.let { put("max_candidates_near_one_component", it) }
                    }
                })
                if (evidence?.scope == graph.scope && distance != null && evidence != null) {
                    runCatching {
                        check(RoadGapPlan.fingerprint(evidence) == graph.fingerprint)
                        RoadGapShapeExport.build(config, evidence, distance)
                    }.getOrNull()?.let { put("road_gap_shapes", it) }
                }
                preview?.takeIf { it.scope == graph.scope && it.selectedCount in 0..8 &&
                    listOfNotNull(it.originGapMeters,it.routeMeters).all { value -> value.isFinite() && value >= 0 && value <= 1_000_000 } }?.let { local ->
                    put("local_route_preview",buildJsonObject {
                        put("phase",local.phase.name); put("selected_count",local.selectedCount)
                        put("origin_reviewed",local.originReviewed)
                        local.originGapMeters?.let { put("origin_gap_meters",kotlin.math.ceil(it)) }
                        local.routeMeters?.let { put("route_meters",kotlin.math.ceil(it)) }
                        local.problem?.let { put("problem",it.name) }
                        local.pointProblem?.let { put("point_problem",it.name) }
                        local.routeProblem?.let { put("route_problem",it.name) }
                    })
                }
                gapReport?.takeIf { it.scope == graph.scope && it.evidenceFingerprint == graph.fingerprint &&
                    it.planned in 0..RoadGapPlan.MAX_REQUESTS && it.outcomes.size <= it.planned &&
                    it.eligibleGapCount >= it.planned }?.let { gaps ->
                    put("road_gap_check", buildJsonObject {
                        put("eligible_gaps", gaps.eligibleGapCount)
                        put("planned", gaps.planned)
                        put("checked", gaps.outcomes.size)
                        put("interrupted", gaps.interrupted)
                        GapCheckOutcome.entries.forEach { outcome ->
                            put(outcome.name.lowercase(), gaps.outcomes.count { it == outcome })
                        }
                    })
                }
            }
        })
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), combined)
    }
}

package io.github.ibudaoa.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.ibudaoa.AppState
import io.github.ibudaoa.capture.AccountProfilePreview
import io.github.ibudaoa.capture.AccountSessionPhase
import io.github.ibudaoa.capture.AccountSessionSnapshot
import io.github.ibudaoa.capture.CertificateSetupPhase
import io.github.ibudaoa.capture.CapturePhase
import io.github.ibudaoa.capture.CaptureState
import io.github.ibudaoa.roads.*
import io.github.ibudaoa.diagnostics.DiagnosticCodec
import io.github.ibudaoa.data.DiagnosticStore
import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.running.AutomaticRunPhase
import io.github.ibudaoa.running.AutomaticRunSummary
import io.github.ibudaoa.running.RunningPlanPhase
import io.github.ibudaoa.running.RunningPlanSummary
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Fixed, invented data: never read device storage, construct a ViewModel or use a gateway. */
private enum class PreviewScene { WARMUP, PREPARATION_RUNNING, PREPARATION_READY, PREPARATION_FAILED, PREPARATION_INTERRUPTED, CERTIFICATE_READY, CERTIFICATE_NOT_INSTALLED, CERTIFICATE_SAVE_FAILED, CERTIFICATE_CHECK_FAILED, RUN_READY, RUNNING, PAUSED, SCHOOL, SETTINGS, SETTINGS_DISCONNECTED }

private fun sampleState(scene: PreviewScene): AppState {
    if (scene == PreviewScene.WARMUP || scene == PreviewScene.SETTINGS_DISCONNECTED) return AppState(loaded = true)
    val certificatePhase = when (scene) {
        PreviewScene.CERTIFICATE_READY -> CertificateSetupPhase.WAITING_INSTALL
        PreviewScene.CERTIFICATE_NOT_INSTALLED -> CertificateSetupPhase.NOT_INSTALLED
        PreviewScene.CERTIFICATE_SAVE_FAILED -> CertificateSetupPhase.SAVE_FAILED
        PreviewScene.CERTIFICATE_CHECK_FAILED -> CertificateSetupPhase.CHECK_FAILED
        else -> null
    }
    if (certificatePhase != null) return AppState(loaded = true, certificateSetup = certificatePhase,
        certificateFileName = if (certificatePhase in setOf(CertificateSetupPhase.WAITING_INSTALL, CertificateSetupPhase.NOT_INSTALLED)) "ibudaoa-连接证书.cer.crt" else null)
    if (scene.name.startsWith("PREPARATION_")) {
        val demoConfig = DiagnosticCodec.decode("""{"school_id":1,"term_id":1,"run_zone_id":1,
            "run_line_info":{"point_list":[
            {"id":"a","jingwei":"30.0,114.0","is_online":1,"type":2,"point_type":1},
            {"id":"b","jingwei":"30.001,114.0","is_online":1,"type":2,"point_type":1}]}}""")
        val plan = RoadBatchPlan.create(demoConfig, 1)
        val phase = when (scene) {
            PreviewScene.PREPARATION_READY -> RoadBatchPhase.COMPLETE
            PreviewScene.PREPARATION_FAILED -> RoadBatchPhase.FAILED
            PreviewScene.PREPARATION_INTERRUPTED -> RoadBatchPhase.INTERRUPTED
            else -> RoadBatchPhase.RUNNING
        }
        return AppState(loaded=true, capturedConfig=true, certificateTrusted=true, mapConsent=true,
            config=demoConfig, connectionEpoch=1, captureRevision=DiagnosticStore.CURRENT_CAPTURE_REVISION,
            busy=phase==RoadBatchPhase.RUNNING,
            roadReport=RoadBatchReport(plan.scope, phase, plan.candidateCount, plan.requests.size,
                if(phase==RoadBatchPhase.COMPLETE) plan.requests.map { RoadLinkSummary(it.fromId,it.toId) } else emptyList()),
            roadEvidence=if(phase==RoadBatchPhase.COMPLETE) RoadEvidenceSnapshot(plan.scope, plan.requests.map {
                CandidateRoadEvidence(it.fromId,it.toId,WalkingLeg(100.0,listOf(listOf(it.from,it.to)))) }) else null)
    }
    val config = DiagnosticConfig(buildJsonObject {
        put("school_name", "示意大学")
        put("run_zone_name", "示意校区")
        put("run_face_verify_config", buildJsonObject {})
    })
    val phase = when (scene) {
        PreviewScene.RUNNING -> AutomaticRunPhase.RUNNING
        PreviewScene.PAUSED -> AutomaticRunPhase.PAUSED
        else -> AutomaticRunPhase.IDLE
    }
    return AppState(
        loaded = true, config = config, capturedConfig = true, mapConsent = true,
        captureRevision = DiagnosticStore.CURRENT_CAPTURE_REVISION,
        capture = CaptureState(phase = CapturePhase.CAPTURED, profile = AccountProfilePreview("示意用户", "示意大学")),
        accountSession = AccountSessionSnapshot(phase = AccountSessionPhase.RESTORED, credentialsPersisted = true),
        runningPlanSummary = RunningPlanSummary(
            phase = RunningPlanPhase.PLANNED, distanceMeters = 2400,
            paceSecondsPerKm = 360, durationSeconds = 864, pointCount = 2,
        ),
        automaticRun = if (phase == AutomaticRunPhase.IDLE) AutomaticRunSummary() else AutomaticRunSummary(
            phase = phase, meters = 960.0, targetMeters = 2400.0, elapsedSeconds = 346,
            remainingSeconds = 518, visitedPoints = 1, requiredPoints = 2,
        ),
    )
}

@Preview(name = "常规", widthDp = 412, heightDp = 915, showSystemUi = true)
@Preview(name = "深色", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES, showSystemUi = true)
private annotation class UiDevices

@UiDevices @Composable private fun PreparationRunningPreview() = ScreenPreview(PreviewScene.PREPARATION_RUNNING)
@UiDevices @Composable private fun PreparationReadyPreview() = ScreenPreview(PreviewScene.PREPARATION_READY)
@UiDevices @Composable private fun PreparationFailedPreview() = ScreenPreview(PreviewScene.PREPARATION_FAILED)
@UiDevices @Composable private fun PreparationInterruptedPreview() = ScreenPreview(PreviewScene.PREPARATION_INTERRUPTED)
@UiDevices @Composable private fun WarmupPreview() = ScreenPreview(PreviewScene.WARMUP)
@UiDevices @Composable private fun CertificateReadyPreview() = ScreenPreview(PreviewScene.CERTIFICATE_READY)
@UiDevices @Composable private fun CertificateNotInstalledPreview() = ScreenPreview(PreviewScene.CERTIFICATE_NOT_INSTALLED)
@UiDevices @Composable private fun CertificateSaveFailedPreview() = ScreenPreview(PreviewScene.CERTIFICATE_SAVE_FAILED)
@UiDevices @Composable private fun CertificateCheckFailedPreview() = ScreenPreview(PreviewScene.CERTIFICATE_CHECK_FAILED)
@UiDevices @Composable private fun RunReadyPreview() = ScreenPreview(PreviewScene.RUN_READY)
@UiDevices @Composable private fun RunningPreview() = ScreenPreview(PreviewScene.RUNNING)
@UiDevices @Composable private fun PausedPreview() = ScreenPreview(PreviewScene.PAUSED)
@UiDevices @Composable private fun SchoolPreview() = ScreenPreview(PreviewScene.SCHOOL)
@UiDevices @Composable private fun SettingsPreview() = ScreenPreview(PreviewScene.SETTINGS)
@UiDevices @Composable private fun DisconnectedSettingsPreview() = ScreenPreview(PreviewScene.SETTINGS_DISCONNECTED)

@Composable
private fun ScreenPreview(scene: PreviewScene) {
    val state = sampleState(scene)
    val destination = when (scene) {
        PreviewScene.SCHOOL -> Destination.SCHOOL
        PreviewScene.SETTINGS, PreviewScene.SETTINGS_DISCONNECTED -> Destination.SETTINGS
        else -> Destination.RUN
    }
    IbudaoaTheme {
        Scaffold(bottomBar = { AppNavigation(destination) {} }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (destination) {
                    Destination.SETTINGS -> SettingsPage(state, {}, {}, {}, {}, {}, {}, {})
                    else -> MapPage(
                        state = state, runningPage = destination == Destination.RUN,
                        onWarmup = {}, onImport = {}, onExport = {}, onExportConnectionReport = {},
                        onSummary = {}, onClear = {}, onCheckRoadGaps = {}, onReviewRoadGaps = {},
                        onReviewRoadDirections = {}, onDisplayFix = {}, onLocationDiagnostics = {},
                        onPrepareLocalPreview = {}, onPrepareRunningPlan = {}, onAutomaticAction = {},
                        onFinishAutomatic = {}, onArchiveAutomatic = {}, mapActive = false,
                        mapBackground = { MapPlaceholder() },
                    )
                }
                Text("开发预览 · 全部数据为示意", Modifier.align(Alignment.Center).padding(12.dp),
                    style = MaterialTheme.typography.labelSmall)
                if (scene == PreviewScene.WARMUP || scene.name.startsWith("CERTIFICATE_") || scene.name.startsWith("PREPARATION_")) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        WarmupDialog(state, {}, {})
                    }
                }
            }
        }
    }
}

/** Shared background across pages; deliberately no SDK, location or plausible road geometry. */
@Composable
private fun MapPlaceholder() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer)) {
        Icon(Icons.Default.Map, contentDescription = "地图占位示意，无实际道路",
            modifier = Modifier.align(Alignment.Center).padding(bottom = 100.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

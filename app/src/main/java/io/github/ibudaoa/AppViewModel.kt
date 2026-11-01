package io.github.ibudaoa

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import io.github.ibudaoa.preview.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.AndroidViewModel
import io.github.ibudaoa.maps.*
import io.github.ibudaoa.roads.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import io.github.ibudaoa.diagnostics.PreparationExport
import androidx.lifecycle.viewModelScope
import io.github.ibudaoa.data.DiagnosticStore
import io.github.ibudaoa.diagnostics.DiagnosticCodec
import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.diagnostics.DiagnosticError
import io.github.ibudaoa.diagnostics.DiagnosticException
import io.github.ibudaoa.domain.SessionState
import io.github.ibudaoa.domain.PointSelectionCache
import java.time.Instant
import java.time.ZoneId
import io.github.ibudaoa.capture.CaptureCertificates
import io.github.ibudaoa.capture.CertificateSetupPhase
import io.github.ibudaoa.capture.CertificateSetupGate
import io.github.ibudaoa.capture.CertificateDownloads
import io.github.ibudaoa.capture.CertificateDownloadRepository
import io.github.ibudaoa.capture.needsCertificateSetup
import io.github.ibudaoa.capture.CapturePurpose
import io.github.ibudaoa.capture.CaptureProblem
import io.github.ibudaoa.capture.CapturePhase
import io.github.ibudaoa.capture.CaptureRuntime
import io.github.ibudaoa.capture.CaptureState
import io.github.ibudaoa.capture.ConnectionReport
import io.github.ibudaoa.capture.AccountSessionRuntime
import io.github.ibudaoa.capture.AccountSessionSnapshot
import io.github.ibudaoa.capture.AccountReadTransport
import io.github.ibudaoa.capture.CaptureVpnService
import io.github.ibudaoa.capture.VerifiedSchoolConfig
import io.github.ibudaoa.running.*
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class AppState(
    val diagnosticMode: Boolean = false,
    val loaded: Boolean = false,
    val config: DiagnosticConfig? = null,
    val preparationAcknowledged: Boolean = false,
    val busy: Boolean = false,
    val session: SessionState = SessionState(),
    val capturedConfig: Boolean = false,
    val certificateTrusted: Boolean = false,
    val certificateSetup: CertificateSetupPhase = CertificateSetupPhase.IDLE,
    val certificateFileName: String? = null,
    val capture: CaptureState = CaptureState(),
    val accountSession:AccountSessionSnapshot=AccountSessionSnapshot(),
    val runningPlanSummary:RunningPlanSummary=RunningPlanSummary(),
    val mapConsent: Boolean = false,
    val locationGranted: Boolean = false,
    val locationCheckCycle: Int = 0,
    val locationStartupPending: Boolean = true,
    val connectionEpoch: Long = 0,
    val captureRevision: Int = 0,
    val capturedAtEpochSecond: Long = 0,
    val preparationError: String? = null,
    val roadReport: RoadBatchReport? = null,
    val roadEvidence: RoadEvidenceSnapshot? = null,
    val roadGraph: ObservedRoadGraph? = null,
    val pointCache: PointSelectionCache? = null,
    val routeResult: RoutePlanResult? = null,
    val gapReport: RoadGapReport? = null,
    val gapChecking: Boolean = false,
    val gapEvidence: RoadGapEvidence? = null,
    val manualGaps: ManualRoadGaps? = null,
    val reviewedDirections: ReviewedRoadDirections? = null,
    val baseRoadGraph: ObservedRoadGraph? = null,
    val gapPlan: RoadGapPlan? = null,
    val displayFix: DisplayFix? = null,
    val locationDiagnostics: LocationDiagnostics? = null,
    val localPreview: LocalRoutePreview? = null,
    val previewPoints: PointSelectionCache? = null,
    val previewSummary: LocalPreviewSummary? = null,
    val automaticRun:AutomaticRunSummary=AutomaticRunSummary(),
    val automaticFailureReason:String?=null,
    val automaticRoute:FixedPointRoute?=null,
    val automaticPosition:io.github.ibudaoa.domain.CampusCoordinate?=null,
    val executionRequirements:RunRuntimeRequirements?=null,
    val updateCheck:io.github.ibudaoa.updates.UpdateSummary=io.github.ibudaoa.updates.UpdateSummary(),
)

internal val AppState.preparationRoadReport: RoadBatchReport?
    get() = roadReport?.takeIf { report ->
        runCatching { RoadBatchPlan.create(config ?: return null, connectionEpoch).scope == report.scope }.getOrDefault(false)
    }

internal val AppState.preparationRoadsReady: Boolean
    get() = capturedConfig && captureRevision == DiagnosticStore.CURRENT_CAPTURE_REVISION &&
        io.github.ibudaoa.domain.PreparationRoads.ready(config, connectionEpoch, roadReport, roadEvidence)

private data class RoadComputation(val base: ObservedRoadGraph?, val graph: ObservedRoadGraph?,
    val route: RoutePlanResult?, val plan: RoadGapPlan?)

enum class CaptureAction { REQUEST_VPN, OPEN_OFFICIAL, STOP_CAPTURE }

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = DiagnosticStore(application)
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    private val actions = Channel<CaptureAction>(Channel.BUFFERED)
    val captureActions = actions.receiveAsFlow()
    private val certificates = CaptureCertificates(application)
    private var roadJob: Job? = null
    private var routeRefreshJob: Job? = null
    private var previewJob: Job? = null
    private val certificateGate = CertificateSetupGate()
    private val certificateDownloads = CertificateDownloadRepository(CertificateDownloads(application))
    private var certificateCheckJob: Job? = null
    private var requestedOpenGeneration = 0L
    private var requestedStopGeneration = 0L
    private var completedCaptureReturnGeneration = 0L
    private var captureFlowActive = CaptureRuntime.state.value.active
    private var accountForeground=false
    private var accountResumeJob:Job?=null
    private var runPreparationJob:Job?=null
    private var automaticJob:Job?=null
    private var automaticController:AutomaticRunController?=null
    private val automaticJournal=AndroidRunTaskJournal(application)
    private var automaticJournalPending=false
    private var automaticJournalChecked=false
    private var preparingRun=false
    private var runGrant:VerifiedSchoolConfig?=null
    private var preparedRunningPlan:RunningPlan?=null
    val captureRequested: Boolean get() = captureFlowActive
    var requestedCapturePurpose: CapturePurpose = CapturePurpose.ACCOUNT_SESSION
        private set

    fun setDiagnosticMode(enabled: Boolean) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { io.github.ibudaoa.running.DiagnosticMode.set(getApplication(), enabled) }
                mutableState.update { it.copy(diagnosticMode=enabled) }
            } catch (_: Exception) { showMessage("调试模式设置未能保存，请重试。") }
        }
    }
    init {
        viewModelScope.launch {
            val enabled=withContext(Dispatchers.IO) {
                val value=io.github.ibudaoa.running.DiagnosticMode.enabled(getApplication())
                if (!value) io.github.ibudaoa.running.DiagnosticMode.clear(getApplication())
                value
            }
            mutableState.update { it.copy(diagnosticMode=enabled) }
        }
        viewModelScope.launch {
            AccountSessionRuntime.session.state.collect { session -> mutableState.update { it.copy(accountSession=session) } }
        }
        viewModelScope.launch { withContext(Dispatchers.IO) { AccountSessionRuntime.initialize(getApplication()) } }
        viewModelScope.launch {
            automaticJournalPending=withContext(Dispatchers.IO) { runCatching { automaticJournal.load()?.let { bytes -> bytes.fill(0);true } ?: false }.getOrDefault(true) }
            automaticJournalChecked=true
            if(automaticJournalPending)mutableState.update { it.copy(session=SessionState(io.github.ibudaoa.domain.SessionPhase.INTERRUPTED,needsReconciliation=true),
                automaticRun=AutomaticRunSummary(AutomaticRunPhase.INTERRUPTED,problem="SAVED_TASK_REQUIRES_RECONCILIATION")) }
        }

        viewModelScope.launch {
            while(true) {
                delay(1000);AccountSessionRuntime.session.expire()
                if(automaticController!=null)publishAutomatic()
                val grant=runGrant
                if(automaticController==null && preparedRunningPlan!=null && grant!=null && !AccountSessionRuntime.session.isCurrent(grant,Instant.now().epochSecond)) {
                    preparedRunningPlan=null;runGrant=null
                    mutableState.update { it.copy(localPreview=null,runningPlanSummary=it.runningPlanSummary.copy(
                        phase=RunningPlanPhase.INTERRUPTED,problem=RunningPlanProblem.ONLINE_EXPIRED)) }
                }
            }
        }
        viewModelScope.launch {
            try {
                store.mapConsent.collect { agreed -> mutableState.update { it.copy(mapConsent = agreed) } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { showMessage("地图授权状态读取失败，暂不启用地图。") }
        }
        viewModelScope.launch {
            try {
                combine(store.pointPlanningStorage, store.preparationAcknowledged) { snapshot, acknowledged ->
                    snapshot to acknowledged
                }.collectLatest { (snapshot, acknowledged) ->
                    if (state.value.connectionEpoch != snapshot.connectionEpoch || state.value.config != snapshot.config)
                        roadJob?.cancel()
                    mutableState.update {
                        val same = it.connectionEpoch == snapshot.connectionEpoch && it.config == snapshot.config
                        val sameRoads = same && it.roadEvidence == snapshot.roadEvidence && it.manualGaps == snapshot.manualGaps &&
                            it.gapEvidence == snapshot.gapEvidence && it.reviewedDirections == snapshot.reviewedDirections
                        it.copy(loaded = true, config = snapshot.config, preparationAcknowledged = acknowledged,
                            runningPlanSummary=if(sameRoads || preparingRun || it.runningPlanSummary.phase==RunningPlanPhase.IDLE)it.runningPlanSummary
                                else it.runningPlanSummary.copy(phase=RunningPlanPhase.INTERRUPTED,problem=RunningPlanProblem.CONFIGURATION_CHANGED),
                            capturedConfig = snapshot.captured, connectionEpoch = snapshot.connectionEpoch,
                            captureRevision = snapshot.captureRevision, capturedAtEpochSecond = snapshot.capturedAtEpochSecond,
                            roadReport = if (same) it.roadReport ?: snapshot.roadReport else snapshot.roadReport,
                            roadEvidence = if (same && it.roadReport?.phase == RoadBatchPhase.RUNNING) it.roadEvidence
                                else snapshot.roadEvidence,
                            roadGraph = if (same && it.roadEvidence == snapshot.roadEvidence && it.manualGaps == snapshot.manualGaps && it.gapEvidence == snapshot.gapEvidence && it.reviewedDirections == snapshot.reviewedDirections) it.roadGraph else null,
                            pointCache = snapshot.cache,
                            localPreview = if (sameRoads) it.localPreview else null,
                            previewPoints = if (same) it.previewPoints else null,
                            previewSummary = if (sameRoads) it.previewSummary else null,
                            gapEvidence = snapshot.gapEvidence,
                            manualGaps = snapshot.manualGaps,
                            reviewedDirections = snapshot.reviewedDirections,
                            baseRoadGraph = if (same && it.roadEvidence == snapshot.roadEvidence && it.manualGaps == snapshot.manualGaps && it.gapEvidence == snapshot.gapEvidence) it.baseRoadGraph else null,
                            gapPlan = if (same && it.roadEvidence == snapshot.roadEvidence) it.gapPlan else null,
                            gapReport = if (same && it.roadEvidence == snapshot.roadEvidence) it.gapReport else null,
                            routeResult = if (same && it.roadEvidence == snapshot.roadEvidence && it.manualGaps == snapshot.manualGaps && it.gapEvidence == snapshot.gapEvidence && it.reviewedDirections == snapshot.reviewedDirections && it.pointCache == snapshot.cache)
                                it.routeResult else null)
                    }
                    val evidence = snapshot.roadEvidence
                    if (snapshot.config != null && evidence != null) {
                        val computed = withContext(Dispatchers.Default) {
                            val metric = AmapDistance()
                            val base = runCatching { ObservedRoadGraphBuilder.build(snapshot.config, evidence, metric, snapshot.gapEvidence, snapshot.manualGaps, automatic = true) }.getOrNull()
                            val graph = base
                            val route = if (graph != null && snapshot.cache != null) runCatching {
                                RouteDraftBuilder.build(snapshot.config, snapshot.captured, snapshot.cache, graph,
                                    metric.implementationId, Instant.now(), ZoneId.of("Asia/Shanghai"), metric)
                            }.getOrNull() else null
                            val plan = runCatching { RoadGapPlan.create(snapshot.config, evidence, metric) }.getOrNull()
                            RoadComputation(base, graph, route, plan)
                        }
                        mutableState.update { current ->
                            if (current.connectionEpoch == snapshot.connectionEpoch && current.config == snapshot.config &&
                                current.roadEvidence == evidence && current.pointCache == snapshot.cache &&
                                current.gapEvidence == snapshot.gapEvidence && current.manualGaps == snapshot.manualGaps && current.reviewedDirections == snapshot.reviewedDirections &&
                                current.roadReport?.phase != RoadBatchPhase.RUNNING)
                                current.copy(baseRoadGraph = current.baseRoadGraph ?: computed.base,
                                    roadGraph = current.roadGraph ?: computed.graph, routeResult = computed.route, gapPlan = computed.plan) else current
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutableState.update { it.copy(loaded = true) }
                messages.send("本地准备状态读取失败，请重新打开应用后重试。")
            }
        }
        viewModelScope.launch {
            CaptureRuntime.state.collect { capture ->
                mutableState.update { it.copy(capture = capture) }
                if (captureFlowActive && capture.phase == CapturePhase.LISTENING && !capture.officialOpened &&
                    requestedOpenGeneration != capture.generation) {
                    requestedOpenGeneration = capture.generation
                    sendCaptureAction(CaptureAction.OPEN_OFFICIAL)
                }
                if (captureFlowActive && capture.phase == CapturePhase.CAPTURED &&
                    requestedStopGeneration != capture.generation) {
                    requestedStopGeneration = capture.generation
                    sendCaptureAction(CaptureAction.STOP_CAPTURE)
                    if(capture.purpose==CapturePurpose.ACCOUNT_SESSION && accountForeground) resumeAccount()
                }
            }
        }
    }

    fun updateDisplayFix(fix: DisplayFix?) { mutableState.update { it.copy(displayFix = fix) } }
    fun updateLocationDiagnostics(snapshot: LocationDiagnostics) { mutableState.update { it.copy(locationDiagnostics=snapshot) } }

    fun closeLocalPreviewReview() {
        previewJob?.cancel()
        if(preparingRun) blockRunningPlan(RunningPlanProblem.ORIGIN)
        mutableState.update { it.copy(localPreview = null) }
    }

    private var runPreparationStage:String?=null
    private fun blockRunningPlan(problem:RunningPlanProblem) {
        preparingRun=false;runGrant=null;preparedRunningPlan=null
        mutableState.update { it.copy(localPreview=null,runningPlanSummary=RunningPlanSummary(RunningPlanPhase.BLOCKED,problem,failureStage=runPreparationStage)) }
        showMessage(problem.label)
    }
    fun prepareRunningPlan(lifecycle:Lifecycle) {
        if (!hasPreciseLocation()) return showMessage("未获得定位权限")
        if(!automaticJournalChecked)return showMessage("正在恢复本地任务状态，请稍后继续。")
        if(automaticTaskOpen())return showMessage("请先核对当前自动任务的结果。")
        automaticController=null
        mutableState.update { it.copy(automaticRun=AutomaticRunSummary(),automaticRoute=null,automaticPosition=null) }
        val initial=state.value
        if(initial.busy || initial.capture.active || roadJob!=null || runPreparationJob?.isActive==true) return
        if(initial.pointCache?.locked==true || initial.session.needsReconciliation || initial.session.phase in setOf(
            io.github.ibudaoa.domain.SessionPhase.PREPARING,io.github.ibudaoa.domain.SessionPhase.RUNNING,
            io.github.ibudaoa.domain.SessionPhase.PAUSED,io.github.ibudaoa.domain.SessionPhase.INTERRUPTED,
            io.github.ibudaoa.domain.SessionPhase.AWAITING_VERDICT,io.github.ibudaoa.domain.SessionPhase.QUERY_FAILED))
            return blockRunningPlan(RunningPlanProblem.TASK_STATE)
        val baseline=initial.config ?: return showMessage("请先完成学校配置。")
        if(!initial.mapConsent || !initial.capturedConfig) return showMessage("请先完成跑前热身。")
        if (initial.roadEvidence == null && initial.roadReport == null && initial.manualGaps == null && initial.reviewedDirections == null) {
            continuePreparation(lifecycle)
            val pending = roadJob ?: return
            runPreparationJob = viewModelScope.launch {
                try {
                    pending.join()
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                    if (state.value.roadEvidence == null) { blockRunningPlan(RunningPlanProblem.ROADS); return@launch }
                    kotlinx.coroutines.withTimeoutOrNull(10_000) { state.first { it.roadGraph != null } }
                    runPreparationJob = null
                    prepareRunningPlan(lifecycle)
                } finally { if (runPreparationJob === currentCoroutineContext()[Job]) runPreparationJob = null }
            }
            return
        }
        if(initial.roadGraph?.edges?.isEmpty()==true && initial.gapPlan?.gaps?.isNotEmpty()==true)
            return blockRunningPlan(RunningPlanProblem.ROADS)
        runPreparationStage="ACCOUNT_READ"
        preparingRun=true;preparedRunningPlan=null;runGrant=null
        mutableState.update { it.copy(busy=true,localPreview=null,runningPlanSummary=RunningPlanSummary(RunningPlanPhase.CHECKING)) }
        runPreparationJob=viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { AccountSessionRuntime.initialize(getApplication()) }
                CaptureVpnService.stopOwned(getApplication())
                var remaining=60
                while(CaptureVpnService.running && remaining-->0) delay(50)
                if(CaptureVpnService.running) return@launch blockRunningPlan(RunningPlanProblem.ACCOUNT)
                withContext(Dispatchers.IO) {
                    AccountSessionRuntime.session.read(AccountReadTransport(maintenance=true),Instant.now().epochSecond,
                        100_000+java.security.SecureRandom().nextInt(900_000),baseline)
                }
                val grant=AccountSessionRuntime.session.verifiedSchool(Instant.now().epochSecond)
                    ?: return@launch blockRunningPlan(RunningPlanProblem.ACCOUNT)
                mutableState.update { it.copy(executionRequirements=grant.executionRequirements) }
                runPreparationStage="CONFIG_COMPATIBILITY"
                if(grant.config!=baseline) {
                    if(!OnlineConfigurationCompatibility.compatible(baseline,grant.config)) return@launch blockRunningPlan(RunningPlanProblem.CONFIGURATION_CHANGED)
                    mutableState.update { it.copy(runningPlanSummary=RunningPlanSummary(RunningPlanPhase.SYNCING)) }
                runPreparationStage="CONFIG_STORAGE"
                    val committed=store.refreshVerified(grant.config,baseline,initial.connectionEpoch) {
                        AccountSessionRuntime.session.isCurrent(grant,Instant.now().epochSecond)
                    }
                runPreparationStage="ROAD_SNAPSHOT"
                    val ready=withContext(Dispatchers.Default) { RunningRoadSnapshot.build(committed,grant.config,AmapDistance()) }
                    if(!AccountSessionRuntime.session.isCurrent(grant,Instant.now().epochSecond) ||
                        store.pointPlanningStorage.first()!=committed)
                        return@launch blockRunningPlan(RunningPlanProblem.CONFIGURATION_CHANGED)
                    // Publish the committed revision and its graph together; UI collectors are not a readiness barrier.
                    mutableState.update { current -> current.copy(config=committed.config,capturedConfig=committed.captured,
                        connectionEpoch=committed.connectionEpoch,captureRevision=committed.captureRevision,
                        capturedAtEpochSecond=committed.capturedAtEpochSecond,roadReport=committed.roadReport,
                        roadEvidence=committed.roadEvidence,gapEvidence=committed.gapEvidence,manualGaps=committed.manualGaps,
                        reviewedDirections=committed.reviewedDirections,pointCache=committed.cache,
                        baseRoadGraph=ready.base,roadGraph=ready.graph,gapPlan=ready.gaps,
                        localPreview=null,previewSummary=null,routeResult=null) }

                }
                if(!AccountSessionRuntime.session.isCurrent(grant,Instant.now().epochSecond)) return@launch blockRunningPlan(RunningPlanProblem.ONLINE_EXPIRED)
                AccountSessionRuntime.session.acknowledgeSchool(grant,Instant.now().epochSecond)
                runPreparationStage="CONFIG_PUBLICATION"
                val config=state.value.config ?: return@launch blockRunningPlan(RunningPlanProblem.CONFIGURATION_CHANGED)
                if(config!=RunningRoadSnapshot.canonical(grant.config)) return@launch blockRunningPlan(RunningPlanProblem.CONFIGURATION_CHANGED)
                val graph=state.value.roadGraph ?: return@launch blockRunningPlan(RunningPlanProblem.ROADS)
                if(graph.edges.isEmpty() && state.value.gapPlan?.gaps?.isNotEmpty()==true)
                    return@launch blockRunningPlan(RunningPlanProblem.ROADS)
                val zone=ZoneId.of("Asia/Shanghai")
                RunningPlanRules.problem(config,Instant.now(),zone)?.let { return@launch blockRunningPlan(it) }
                mutableState.update { it.copy(runningPlanSummary=RunningPlanSummary(RunningPlanPhase.WAITING_LOCATION)) }
                runPreparationStage="LOCATION"
                val located=withTimeout(20_000) { state.first { current -> current.displayFix?.let { fix ->
                    fix.accuracyMeters<=50 && SystemClock.elapsedRealtime()-fix.receivedElapsedMillis in 0..15_000
                }==true } }
                if(!AccountSessionRuntime.session.isCurrent(grant,Instant.now().epochSecond)) return@launch blockRunningPlan(RunningPlanProblem.ONLINE_EXPIRED)
                val policy=runCatching { io.github.ibudaoa.domain.PointPolicyFactory.create(config,
                    Instant.now().atZone(zone).toLocalTime().toSecondOfDay(),AmapDistance().implementationId) }.getOrNull()
                    ?: return@launch blockRunningPlan(RunningPlanProblem.SCHOOL_RULES)
                val cached=initial.previewPoints?.takeIf { it.scope.policyFingerprint==policy.fingerprint && it.scope.schoolId==policy.schoolId &&
                    it.scope.termId==policy.termId && it.scope.zoneId==policy.zoneId }?.copy(scope=policy.scope(graph.scope.connectionEpoch))
                    ?: state.value.previewPoints
                mutableState.update { it.copy(runningPlanSummary=RunningPlanSummary(RunningPlanPhase.CONNECTING_ROUTE)) }
                runPreparationStage="ACCESS_ROUTE"
                val target=OriginRoadProposalBuilder.queryTarget(graph,located.displayFix!!.coordinate,AmapDistance())
                    ?: return@launch blockRunningPlan(RunningPlanProblem.ACCESS_ROUTE)
                val access=if(target.gapMeters<=OriginRoadProposalBuilder.MAX_GAP_METERS)
                    OriginRoadProposalBuilder.create(graph,target.origin,AmapDistance())
                else {
                    val paths=AmapWalkingRoadSource(getApplication()).probeAccess(target.origin,target.roadPoint,true,lifecycle)
                    OriginRoadProposalBuilder.routed(graph,target,paths,AmapDistance())
                } ?: return@launch blockRunningPlan(RunningPlanProblem.ACCESS_ROUTE)
                runPreparationStage="GRAPH_REVISION"
                if(state.value.config!=config || state.value.roadGraph!==graph) return@launch blockRunningPlan(RunningPlanProblem.CONFIGURATION_CHANGED)
                val result=withContext(Dispatchers.Default) { LocalRoutePreviewEngine.prepare(config,true,graph,state.value.displayFix,
                    cached,Instant.now(),SystemClock.elapsedRealtime(),zone,AmapDistance(),access) }
                when(result) {
                    is PreviewPreparation.Ready -> {
                        runGrant=grant
                        mutableState.update { it.copy(localPreview=result.preview,previewPoints=result.preview.points,
                            previewSummary=LocalPreviewSummary(graph.scope,PreviewPhase.REVIEW_ORIGIN,result.preview.points.selectedIds.size,originGapMeters=result.preview.proposal.gapMeters),
                            runningPlanSummary=RunningPlanSummary(RunningPlanPhase.REVIEW_ORIGIN)) }
                    }
                    is PreviewPreparation.Blocked -> blockRunningPlan(when(result.problem) {
                        PreviewProblem.LOCATION_REQUIRED,PreviewProblem.POSITION_CHANGED -> RunningPlanProblem.LOCATION
                        PreviewProblem.POINT_SELECTION -> RunningPlanProblem.POINT_SELECTION
                        else -> RunningPlanProblem.ROADS
                    })
                }
            } catch(_:kotlinx.coroutines.TimeoutCancellationException) { blockRunningPlan(when(state.value.runningPlanSummary.phase) {
                RunningPlanPhase.WAITING_LOCATION -> RunningPlanProblem.LOCATION
                RunningPlanPhase.SYNCING -> RunningPlanProblem.SYNC_INCOMPLETE
                else -> RunningPlanProblem.ROADS
            }) }
            catch(e:CancellationException) { throw e }
            catch(_:RoadProbeException) { blockRunningPlan(RunningPlanProblem.ACCESS_ROUTE) }
            catch(_:Exception) { blockRunningPlan(RunningPlanProblem.CONFIGURATION_CHANGED) }
            finally { mutableState.update { it.copy(busy=false) };runPreparationJob=null }
        }
    }

    private fun automaticTaskOpen():Boolean = automaticJournalPending || automaticController?.summary?.let {
        it.phase !in setOf(AutomaticRunPhase.VALID,AutomaticRunPhase.INVALID,AutomaticRunPhase.IDLE)
    }==true
    private fun publishAutomatic() {
        val summary=automaticController?.summary ?: return
        val phase=when(summary.phase) {
            AutomaticRunPhase.STARTING -> io.github.ibudaoa.domain.SessionPhase.PREPARING
            AutomaticRunPhase.RUNNING -> io.github.ibudaoa.domain.SessionPhase.RUNNING
            AutomaticRunPhase.PAUSED -> io.github.ibudaoa.domain.SessionPhase.PAUSED
            AutomaticRunPhase.FINISHING,AutomaticRunPhase.AWAITING_VERDICT -> io.github.ibudaoa.domain.SessionPhase.AWAITING_VERDICT
            AutomaticRunPhase.VALID -> io.github.ibudaoa.domain.SessionPhase.VALID
            AutomaticRunPhase.INVALID -> io.github.ibudaoa.domain.SessionPhase.INVALID
            else -> io.github.ibudaoa.domain.SessionPhase.INTERRUPTED
        }
        mutableState.update { it.copy(automaticRun=summary,automaticPosition=automaticController?.timeline?.current()?.coordinate,automaticFailureReason=automaticController?.ledger?.failureReasonForDisplay(),session=SessionState(phase,needsReconciliation=summary.phase==AutomaticRunPhase.INTERRUPTED)) }
    }
    fun automaticAction(lifecycle:Lifecycle) {
        if(state.value.busy || !automaticJournalChecked)return
        val existing=automaticController
        if(existing!=null) {
            when(existing.summary.phase) {
                AutomaticRunPhase.RUNNING -> { existing.pause();publishAutomatic();return }
                AutomaticRunPhase.PAUSED -> { existing.resume();publishAutomatic();return }
                AutomaticRunPhase.AWAITING_VERDICT,AutomaticRunPhase.INTERRUPTED -> { queryAutomatic();return }
                AutomaticRunPhase.VALID,AutomaticRunPhase.INVALID -> {
                    if(automaticJournalPending) {
                        mutableState.update { it.copy(busy=true) }
                        viewModelScope.launch {
                            try { releaseAutomaticIfResolved();mutableState.update { it.copy(busy=false) };prepareRunningPlan(lifecycle) }
                            catch(_:Exception) { mutableState.update { it.copy(busy=false) };showMessage("记录已核对，本地状态尚未清理，请稍后重试。") }
                        }
                    } else prepareRunningPlan(lifecycle)
                    return
                }
                else -> return
            }
        }
        if(automaticJournalPending)return restoreAutomaticResult()
        val snapshot=state.value
        if(snapshot.busy || snapshot.capture.active || automaticJob?.isActive==true || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))return
        val plan=preparedRunningPlan ?: return showMessage("请先生成本次运行计划。")
        val grant=runGrant ?: return showMessage("请重新核对运行计划。")
        val config=snapshot.config ?: return
        if(!AccountSessionRuntime.session.isCurrent(grant,Instant.now().epochSecond))return blockRunningPlan(RunningPlanProblem.ONLINE_EXPIRED)
        if(!grant.executionRequirements.automaticSupported)return showMessage("当前账号的运动校验配置尚未适配，请导出诊断；不需要实际走跑。")
        val selected=snapshot.previewPoints ?: return
        if(selected.selectedIds.any { it.toLongOrNull()?.let { value -> value>0 }!=true })return showMessage("当前点位编号格式尚未适配，尚未启动任务。")
        if(selected.selectedIds!=plan.route.selectedIds || selected.locked)return showMessage("本次固定点位状态已变化，请重新核对。")
        mutableState.update { it.copy(busy=true,automaticRun=AutomaticRunSummary(AutomaticRunPhase.STARTING)) }
        automaticJob=viewModelScope.launch {
            var created:RunTaskLedger?=null
            try {
                val candidates=io.github.ibudaoa.domain.ConfigurationReviewer.review(config).candidates ?: error("Unknown points")
                val points=candidates.filter { it.id in plan.route.selectedIds }.associate { it.id to it.coordinate }
                val radius=ClientCheckpointRadius.meters(config) ?: error("Unknown checkpoint rule")
                val timeline=AutomaticRouteRun(plan.route,points,radius,AmapDistance(),plan.targetPaceSecondsPerKm)
                val account=AccountSessionRuntime.session.runAccountId() ?: error("Account not ready")
                val ledger=RunTaskLedger.create(automaticJournal,account,plan.route.scope.schoolId,
                    plan.route.scope.termId,plan.route.scope.zoneId,plan.route.selectedIds,true,grant.executionRequirements.gyroscopeRequired==true)
                created=ledger
                automaticJournalPending=true
                store.savePointCache(selected)
                store.savePointCache(selected.lockForExecution())
                val gateway=io.github.ibudaoa.capture.AutomaticRunGateway(AccountSessionRuntime.session,config,grant.executionRequirements)
                val controller=AutomaticRunController(ledger,timeline,gateway,grant.executionRequirements.gyroscopeRequired==true,
                    { accountForeground && AccountSessionRuntime.session.runAccountId()==account },
                    { AccountSessionRuntime.session.isCurrent(grant,Instant.now().epochSecond) },{ SystemClock.elapsedRealtime() })
                automaticController=controller
                mutableState.update { it.copy(automaticRoute=plan.route) };publishAutomatic()
                if(!controller.start()) { publishAutomatic();return@launch }
                mutableState.update { it.copy(busy=false) };publishAutomatic()
                while(controller.summary.phase in setOf(AutomaticRunPhase.RUNNING,AutomaticRunPhase.PAUSED,AutomaticRunPhase.FINISHING)) {
                    controller.tick();publishAutomatic();delay(500)
                }
                releaseAutomaticIfResolved()
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) {
                runCatching { automaticController?.interrupt() };publishAutomatic()
                if(automaticController==null)mutableState.update { it.copy(automaticRun=AutomaticRunSummary(AutomaticRunPhase.FAILED,problem="PREPARATION_FAILED")) }
                showMessage("自动执行未完成，请导出诊断；不会重复提交未知结果的请求。")
            } finally {
                val unsent=created
                if(unsent!=null && unsent.canAbandonUnsent())withContext(NonCancellable) {
                    runCatching {
                        store.pointPlanningStorage.first().cache?.takeIf { it.locked }?.let { store.releaseResolvedPointCache(it) }
                        unsent.abandonUnsent();automaticJournalPending=false;automaticController=null
                        mutableState.update { it.copy(automaticRun=AutomaticRunSummary(),session=SessionState()) }
                    }
                }
                mutableState.update { it.copy(busy=false) };if(automaticJob===currentCoroutineContext()[Job])automaticJob=null
            }
        }
    }
    private suspend fun releaseAutomaticIfResolved() {
        val controller=automaticController ?: return
        publishAutomatic()
        if(!automaticJournalPending)return
        if(controller.summary.phase in setOf(AutomaticRunPhase.VALID,AutomaticRunPhase.INVALID)) {
            withContext(Dispatchers.IO) {
                controller.ledger.archiveResolved()
            }
            val cache=store.pointPlanningStorage.first().cache
            if(cache?.locked==true)store.releaseResolvedPointCache(cache)
            withContext(Dispatchers.IO) { controller.ledger.releaseResolved() }
            automaticJournalPending=false
        }
    }
    fun finishAutomatic() {
        val controller=automaticController ?: return
        if(controller.summary.phase !in setOf(AutomaticRunPhase.RUNNING,AutomaticRunPhase.PAUSED))return
        if(state.value.busy || automaticJob?.isActive==true && controller.ledger.summary.writePending)return
        controller.pause()
        val previous=automaticJob
        automaticJob=viewModelScope.launch {
            previous?.cancel();previous?.join()
            mutableState.update { it.copy(busy=true) }
            try { controller.finish();publishAutomatic();releaseAutomaticIfResolved() }
            catch(e:CancellationException) { throw e }
            catch(_:Exception) { runCatching { controller.interrupt() };publishAutomatic();showMessage("结束结果尚未确认，请核对记录。") }
            finally { mutableState.update { it.copy(busy=false) };if(automaticJob===currentCoroutineContext()[Job])automaticJob=null }
        }
    }
    fun onAutomaticForegroundReturned() {
        if(automaticController!=null || automaticJournalPending)AccountSessionRuntime.session.onForeground()
    }
    fun interruptAutomatic() {
        val controller=automaticController
        if(controller==null) { automaticJob?.cancel();return }
        if(controller.summary.phase in setOf(AutomaticRunPhase.VALID,AutomaticRunPhase.INVALID))return
        runCatching { controller.interrupt() };automaticJob?.cancel();publishAutomatic()
        AccountSessionRuntime.session.onBackground()
    }
    fun archiveManuallyReviewedAutomatic() {
        if(state.value.busy || automaticJob?.isActive==true)return
        mutableState.update { it.copy(busy=true) }
        automaticJob=viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { AccountSessionRuntime.initialize(getApplication()) }
                val account=AccountSessionRuntime.session.runAccountId() ?: error("Account unavailable")
                val ledger=withContext(Dispatchers.IO) { RunTaskLedger.restore(automaticJournal,account) } ?: error("No pending task")
                ledger.foreground();check(ledger.canArchiveAfterManualNoRecord())
                withContext(Dispatchers.IO) { automaticJournal.archiveManuallyReviewed() }
                store.pointPlanningStorage.first().cache?.takeIf { it.locked }?.let { store.releaseResolvedPointCache(it) }
                withContext(Dispatchers.IO) { automaticJournal.clear() }
                automaticJournalPending=false;automaticController=null;preparedRunningPlan=null;runGrant=null
                mutableState.update { it.copy(automaticRun=AutomaticRunSummary(),automaticRoute=null,automaticPosition=null,automaticFailureReason=null,
                    session=SessionState(),runningPlanSummary=RunningPlanSummary(),localPreview=null) }
                showMessage("已归档本地待核对任务，未删除平台记录，也没有重传结束请求。")
            } catch(_:Exception) { showMessage("本地任务未能归档，原任务和配置已保留。请导出诊断。") }
            finally { mutableState.update { it.copy(busy=false) };if(automaticJob===currentCoroutineContext()[Job])automaticJob=null }
        }
    }
    private fun queryAutomatic() {
        val controller=automaticController ?: return
        if(state.value.busy || automaticJob?.isActive==true)return
        automaticJob=viewModelScope.launch {
            mutableState.update { it.copy(busy=true) }
            try {
                controller.returned()
                if(!controller.summary.recordBound) { showMessage("尚未取得上次任务的具体记录，不能重开或恢复推进。请导出诊断。");return@launch }
                controller.query();publishAutomatic();releaseAutomaticIfResolved()
            }
            catch(_:Exception) { showMessage("平台记录尚未核对完成，请保留当前任务。") }
            finally { mutableState.update { it.copy(busy=false) };if(automaticJob===currentCoroutineContext()[Job])automaticJob=null }
        }
    }
    private fun restoreAutomaticResult() {
        if(state.value.busy)return
        automaticJob=viewModelScope.launch {
            mutableState.update { it.copy(busy=true) }
            try {
                withContext(Dispatchers.IO) { AccountSessionRuntime.initialize(getApplication()) }
                val account=AccountSessionRuntime.session.runAccountId() ?: error("Account unavailable")
                val ledger=withContext(Dispatchers.IO) { RunTaskLedger.restore(automaticJournal,account) } ?: error("No pending task")
                ledger.foreground()
                mutableState.update { it.copy(automaticRun=it.automaticRun.copy(platformStarted=ledger.summary.platformStarted,recordBound=ledger.summary.recordBound,pendingWrite=ledger.pendingWrite()?.name,finishStage=ledger.finishStage(),identityChecks=ledger.identityChecks(),problem=ledger.lastVerdict()?.let { "RECORD_$it" })) }
                if(!ledger.summary.recordBound) { showMessage("上次自动任务结果尚未确认，暂不能重开或恢复推进。请导出诊断。");return@launch }
                val context=ledger.requestContext()
                val gateway=io.github.ibudaoa.capture.RunRecordReader(AccountSessionRuntime.session)
                val body=gateway.record(context,ledger.expectedRecord())
                val result=try { ledger.verifyRecord(body) } finally { body.fill(0) }
                mutableState.update { it.copy(
                    automaticFailureReason=ledger.failureReasonForDisplay(),
                    automaticRun=it.automaticRun.copy(identityChecks=ledger.identityChecks(),problem="RECORD_${result.verdict.name}",
                        serverPointCount=ledger.serverPointCount(),serverStatus=ledger.serverRecordStatus())) }
                if(result.identityMatched && result.verdict in setOf(RunRecordVerdict.VALID,RunRecordVerdict.INVALID)) {
                    withContext(Dispatchers.IO) {
                        ledger.archiveResolved()
                    }
                    store.pointPlanningStorage.first().cache?.takeIf { it.locked }?.let { store.releaseResolvedPointCache(it) }
                    withContext(Dispatchers.IO) { ledger.releaseResolved() };automaticJournalPending=false
                    mutableState.update { it.copy(session=SessionState(if(result.platformValid)io.github.ibudaoa.domain.SessionPhase.VALID else io.github.ibudaoa.domain.SessionPhase.INVALID),
                        automaticRun=it.automaticRun.copy(phase=if(result.platformValid)AutomaticRunPhase.VALID else AutomaticRunPhase.INVALID,recordBound=true,recordVerified=true)) }
                } else showMessage("具体记录判定尚未确认，请保留任务并导出诊断。")
            } catch(_:Exception) { showMessage("保存的自动任务尚未核对，请导出诊断。") }
            finally { mutableState.update { it.copy(busy=false) };if(automaticJob===currentCoroutineContext()[Job])automaticJob=null }
        }
    }

    fun prepareLocalPreview() {
        val snapshot = state.value
        if (snapshot.busy || snapshot.capture.active || !snapshot.mapConsent || roadJob != null) return
        val config = snapshot.config ?: return
        val graph = snapshot.roadGraph ?: return showMessage("道路资料尚未就绪。")
        preparingRun=false;runGrant=null;preparedRunningPlan=null
        mutableState.update { it.copy(runningPlanSummary=RunningPlanSummary()) }
        mutableState.update { it.copy(busy = true, localPreview = null) }
        previewJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    LocalRoutePreviewEngine.prepare(config,snapshot.capturedConfig,graph,snapshot.displayFix,
                        snapshot.previewPoints,Instant.now(),SystemClock.elapsedRealtime(),ZoneId.of("Asia/Shanghai"),AmapDistance())
                }
                if (state.value.config != config || state.value.roadGraph !== graph) return@launch
                when (result) {
                    is PreviewPreparation.Ready -> mutableState.update { it.copy(localPreview=result.preview,
                        previewPoints=result.preview.points,previewSummary=LocalPreviewSummary(graph.scope,PreviewPhase.REVIEW_ORIGIN,
                            result.preview.points.selectedIds.size,originGapMeters=result.preview.proposal.gapMeters)) }
                    is PreviewPreparation.Blocked -> {
                        mutableState.update { it.copy(previewSummary=LocalPreviewSummary(graph.scope,PreviewPhase.BLOCKED,
                            problem=result.problem,pointProblem=result.pointProblem)) }
                        showMessage(if (result.problem == PreviewProblem.LOCATION_REQUIRED)
                            state.value.locationDiagnostics?.label ?: result.problem.label
                            else result.pointProblem?.label ?: result.problem.label)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { showMessage("本地路线预览未完成，请检查定位和道路资料。") }
            finally { mutableState.update { it.copy(busy=false) }; previewJob=null }
        }
    }

    fun confirmLocalPreviewOrigin() {
        val snapshot = state.value
        if (snapshot.busy || snapshot.capture.active) return
        if(preparingRun && runGrant?.let { AccountSessionRuntime.session.isCurrent(it,Instant.now().epochSecond) }!=true)
            return blockRunningPlan(RunningPlanProblem.ONLINE_EXPIRED)
        val config=snapshot.config ?: return
        if(preparingRun && runGrant?.config?.let(RunningRoadSnapshot::canonical)!=config) return blockRunningPlan(RunningPlanProblem.CONFIGURATION_CHANGED)
        val graph=snapshot.roadGraph ?: return
        val preview=snapshot.localPreview ?: return
        mutableState.update { it.copy(busy=true) }
        previewJob=viewModelScope.launch {
            try {
                val (updated,summary)=withContext(Dispatchers.Default) {
                    LocalRoutePreviewEngine.confirm(config,snapshot.capturedConfig,graph,preview,snapshot.displayFix,
                        Instant.now(),SystemClock.elapsedRealtime(),ZoneId.of("Asia/Shanghai"),AmapDistance())
                }
                if (state.value.config != config || state.value.roadGraph !== graph || state.value.localPreview !== preview) return@launch
                val after=LocalRoutePreviewEngine.reviewProblem(config,state.value.capturedConfig,graph,preview,state.value.displayFix,
                    Instant.now(),SystemClock.elapsedRealtime(),ZoneId.of("Asia/Shanghai"),AmapDistance())
                if (after != null) {
                    if(preparingRun) return@launch blockRunningPlan(if(after in setOf(PreviewProblem.LOCATION_REQUIRED,PreviewProblem.POSITION_CHANGED))
                        RunningPlanProblem.LOCATION else RunningPlanProblem.ROADS)
                    mutableState.update { it.copy(localPreview=null,previewSummary=LocalPreviewSummary(graph.scope,PreviewPhase.BLOCKED,
                        preview.points.selectedIds.size,problem=after)) }
                    showMessage(after.label)
                    return@launch
                }
                if(preparingRun) {
                    val grant=runGrant
                    if(grant==null || !AccountSessionRuntime.session.isCurrent(grant,Instant.now().epochSecond))
                        return@launch blockRunningPlan(RunningPlanProblem.ONLINE_EXPIRED)
                    val route=updated?.route ?: run {
                        mutableState.update { it.copy(localPreview=updated, previewSummary=summary) }
                        val reason = when (summary.routeProblem) {
                            io.github.ibudaoa.roads.RoutePlanProblem.NO_OBSERVED_ROUTE -> RunningPlanProblem.ROAD_UNREACHABLE
                            io.github.ibudaoa.roads.RoutePlanProblem.NO_DISTANCE_MARGIN -> RunningPlanProblem.ROAD_DISTANCE
                            io.github.ibudaoa.roads.RoutePlanProblem.SEARCH_LIMIT -> RunningPlanProblem.ROAD_CALCULATION
                            io.github.ibudaoa.roads.RoutePlanProblem.START_NOT_ON_ROAD -> RunningPlanProblem.ACCESS_ROUTE
                            else -> RunningPlanProblem.ROADS
                        }
                        return@launch blockRunningPlan(reason)
                    }
                    val (plan,planSummary)=RunningPlanRules.create(config,route,Instant.now(),ZoneId.of("Asia/Shanghai"))
                    preparedRunningPlan=plan;preparingRun=false
                    mutableState.update { it.copy(localPreview=updated,previewSummary=summary,runningPlanSummary=planSummary) }
                    showMessage(planSummary.problem?.label ?: "本次距离、配速和路线计划已生成，尚未创建运动。")
                    return@launch
                }
                mutableState.update { it.copy(localPreview=updated,previewSummary=summary) }
                showMessage(if (updated?.route != null) "本地路线草稿已生成，尚未开始运动。"
                    else summary.problem?.label ?: "当前道路尚不能生成完整草稿。")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if(preparingRun) blockRunningPlan(RunningPlanProblem.ROADS)
                else { mutableState.update { it.copy(localPreview=null) };showMessage("本地路线计算未完成，请重试。") }
            }
            finally { mutableState.update { it.copy(busy=false) }; previewJob=null }
        }
    }

    fun acknowledgePreparation() = operation {
        store.acknowledgePreparation()
    }

    private fun hasPreciseLocation(): Boolean = getApplication<Application>().checkSelfPermission(
        android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun onLocationStartup() {
        mutableState.update { it.copy(locationCheckCycle = it.locationCheckCycle + 1, locationStartupPending = true) }
        refreshLocationPermission()
    }

    fun acknowledgeLocationStartup() {
        mutableState.update { it.copy(locationStartupPending = false) }
    }

    fun refreshLocationPermission() {
        val granted = hasPreciseLocation()
        mutableState.update { it.copy(locationGranted = granted) }
    }

    fun setMapConsent(agreed: Boolean) = operation("地图授权状态未能保存，请重试。") {
        store.setMapConsent(agreed)
    }

    fun continueAccountReadCheck() = requestCapture(CapturePurpose.ACCOUNT_READ_CHECK)
    fun continueAccountSession() {
        if(automaticController?.summary?.phase in setOf(AutomaticRunPhase.STARTING,AutomaticRunPhase.RUNNING,AutomaticRunPhase.PAUSED,AutomaticRunPhase.FINISHING))
            return showMessage("自动任务进行中，请先结束并核对记录。")
        requestCapture(CapturePurpose.ACCOUNT_SESSION)
    }
    fun disconnectAccountSession() = operation("当前自动任务尚未核对，不能退出账号。") {
        check(!automaticTaskOpen())
        withContext(Dispatchers.IO) { AccountSessionRuntime.initialize(getApplication());AccountSessionRuntime.session.clear() }
    }
    fun verifyAccountSession() = operation("自动任务进行中或会话读取未完成，请稍后核对。") {
        check(automaticController?.summary?.phase !in setOf(AutomaticRunPhase.STARTING,AutomaticRunPhase.RUNNING,AutomaticRunPhase.PAUSED,AutomaticRunPhase.FINISHING))
        withContext(Dispatchers.IO) { AccountSessionRuntime.initialize(getApplication()) }
        CaptureVpnService.stopOwned(getApplication())
        var remaining=60
        while(CaptureVpnService.running && remaining-->0) delay(50)
        if(CaptureVpnService.running) {
            messages.send("临时连接尚未关闭，本次没有发出会话请求。")
            return@operation
        }
        withContext(Dispatchers.IO) {
            AccountSessionRuntime.session.read(AccountReadTransport(maintenance=true),System.currentTimeMillis()/1000,
                100_000+java.security.SecureRandom().nextInt(900_000),state.value.config)
        }
    }
    private fun resumeAccount() {
        if(!accountForeground || accountResumeJob?.isActive==true) return
        accountResumeJob=viewModelScope.launch {
            withContext(Dispatchers.IO) {
                AccountSessionRuntime.initialize(getApplication())
                if (accountForeground) AccountSessionRuntime.session.onForeground()
            }
            val completedCapture = state.value.capture
            if (completedCapture.phase == CapturePhase.CAPTURED && completedCapture.purpose == CapturePurpose.ACCOUNT_SESSION &&
                completedCapture.generation != completedCaptureReturnGeneration &&
                AccountSessionRuntime.session.state.value.acquisition?.readAccepted == true) {
                completedCaptureReturnGeneration = completedCapture.generation
                captureFlowActive = false
                return@launch // This acquisition just verified and saved the same login and school.
            }
            var remaining=100
            while(accountForeground && state.value.busy && remaining-->0) delay(50)
            // An explicit connection can be awaiting system VPN consent before the service
            // publishes STARTING. Automatic account verification must not stop that start.
            if (captureFlowActive && state.value.capture.phase != CapturePhase.CAPTURED) return@launch
            val account=AccountSessionRuntime.session.state.value
            if(accountForeground && !state.value.capture.active && (account.phase==io.github.ibudaoa.capture.AccountSessionPhase.RESTORED ||
                account.phase==io.github.ibudaoa.capture.AccountSessionPhase.READY && account.lastRead==null)) verifyAccountSession()
        }
    }
    fun continueCapture() {
        if(automaticTaskOpen())return showMessage("请先核对当前自动任务，原配置会保留。")
        requestCapture(CapturePurpose.ACCOUNT_SESSION)
    }

    fun reconnectAccount() {
        if(automaticTaskOpen())return showMessage("请先核对当前自动任务，原配置会保留。")
        requestCapture(CapturePurpose.ACCOUNT_SESSION,forceCapture=true)
    }

    private fun requestCapture(purpose: CapturePurpose, forceCapture:Boolean=false) {
        if (state.value.busy || roadJob != null) return
        if (!state.value.capture.active) requestedCapturePurpose=purpose
        captureFlowActive = true
        certificateCheckJob?.cancel()
        val setupGeneration = certificateGate.begin()
        operation("准备连接未完成，请返回后重试。") {
            if (state.value.capture.active) {
                if (state.value.capture.phase == CapturePhase.LISTENING) sendCaptureAction(CaptureAction.OPEN_OFFICIAL)
                return@operation
            }
            if (purpose!=CapturePurpose.ACCOUNT_READ_CHECK) store.acknowledgePreparation()
            // A confirmed retained session can fill school data without reopening a VPN.
            if (purpose == CapturePurpose.ACCOUNT_SESSION && accountForeground && !forceCapture) {
                val recovery = withContext(Dispatchers.IO) {
                    AccountSessionRuntime.initialize(getApplication())
                    val session = AccountSessionRuntime.session
                    if (accountForeground && certificateGate.accepts(setupGeneration)) session.onForeground()
                    val account = session.state.value
                    if (account.available && account.protocolReady) {
                        session.read(AccountReadTransport(maintenance=true, trace={event ->
                            android.util.Log.i("CaptureStage","setup=$setupGeneration stage=${event.stage.name} code=${event.failure.name} ms=${event.millis}")
                        }), Instant.now().epochSecond,
                            100_000+java.security.SecureRandom().nextInt(900_000),state.value.config)
                    }
                    val verified=session.verifiedSchool(Instant.now().epochSecond)
                    verified to io.github.ibudaoa.capture.AccountRecoveryPolicy.decide(session.state.value,verified!=null)
                }
                if (!certificateGate.accepts(setupGeneration) || !captureFlowActive || !accountForeground) return@operation
                val restored=recovery.first
                if (recovery.second == io.github.ibudaoa.capture.AccountRecovery.RETRY_READ) {
                    captureFlowActive=false;certificateGate.cancel()
                    val failed=CaptureRuntime.begin(purpose)
                    CaptureRuntime.update(failed) { it.copy(phase=CapturePhase.ERROR,problem=CaptureProblem.NETWORK,
                        message="账号读取暂未完成，请重试；已有登录和学校资料已保留。") }
                    return@operation
                }
                if (restored != null) {
                    withContext(Dispatchers.IO) { store.save(restored.config,captured=true) }
                    if (!certificateGate.accepts(setupGeneration) || !captureFlowActive) return@operation
                    captureFlowActive=false
                    certificateGate.cancel()
                    val reusedGeneration = CaptureRuntime.begin(purpose)
                    completedCaptureReturnGeneration = reusedGeneration
                    CaptureRuntime.update(reusedGeneration) { it.copy(phase=CapturePhase.CAPTURED,
                        config=restored.config,accountRead=AccountSessionRuntime.session.state.value.lastRead,
                        message="已恢复账号并读取学校资料。") }
                    mutableState.update { it.copy(certificateSetup=CertificateSetupPhase.INSTALLED) }
                    return@operation
                }
            }
            if (!certificateGate.accepts(setupGeneration) || !captureFlowActive) return@operation
            mutableState.update { it.copy(certificateSetup = CertificateSetupPhase.CHECKING) }
            val trusted = try { withContext(Dispatchers.IO) { certificates.isInstalled() } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (certificateGate.accepts(setupGeneration)) mutableState.update { it.copy(certificateSetup = CertificateSetupPhase.CHECK_FAILED) }
                return@operation
            }
            if (!certificateGate.accepts(setupGeneration) || !captureFlowActive) return@operation
            mutableState.update { it.copy(certificateTrusted = trusted) }
            if (trusted) {
                mutableState.update { it.copy(certificateSetup = CertificateSetupPhase.INSTALLED) }
                if (certificateGate.installed(setupGeneration)) sendCaptureAction(CaptureAction.REQUEST_VPN)
            } else {
                mutableState.update { it.copy(certificateSetup = CertificateSetupPhase.SAVING) }
                val file = try { withContext(Dispatchers.IO) { certificateDownloads.ensure(certificates.root().encoded) } }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) {
                    if (certificateGate.accepts(setupGeneration)) mutableState.update { it.copy(certificateSetup = CertificateSetupPhase.SAVE_FAILED) }
                    return@operation
                }
                if (!certificateGate.accepts(setupGeneration) || !captureFlowActive) return@operation
                mutableState.update { it.copy(certificateFileName = file.fileName, certificateSetup = CertificateSetupPhase.WAITING_INSTALL) }
                certificateGate.waiting(setupGeneration)
            }
        }
    }

    fun continuePreparation(lifecycle: Lifecycle) {
        val snapshot = state.value
        if (snapshot.busy || roadJob != null) return
        if (snapshot.capture.active) { requestCapture(snapshot.capture.purpose);return }
        if (snapshot.capture.phase == CapturePhase.ERROR) { requestCapture(requestedCapturePurpose);return }
        if (needsCertificateSetup(snapshot.certificateSetup, snapshot.capturedConfig, snapshot.certificateTrusted, snapshot.capture.active)) {
            requestCapture(requestedCapturePurpose)
            return
        }
        if (snapshot.preparationRoadsReady) return
        if (!snapshot.manualGaps?.indices.isNullOrEmpty() || !snapshot.reviewedDirections?.reversible.isNullOrEmpty())
            return mutableState.update { it.copy(preparationError = "当前道路含手动确认，无法自动重新核对。") }
        if (!snapshot.capturedConfig || snapshot.captureRevision != DiagnosticStore.CURRENT_CAPTURE_REVISION) {
            continueCapture()
            return
        }
        val config = snapshot.config ?: return
        if (!hasPreciseLocation() || !snapshot.mapConsent || !BuildConfig.MAP_KEY_CONFIGURED || snapshot.capture.active) return
        val plan = runCatching { RoadBatchPlan.create(config, snapshot.connectionEpoch) }.getOrNull()
            ?: return mutableState.update { it.copy(preparationError = "当前学校配置暂时无法核对道路，请重试。") }
        CaptureVpnService.stopOwned(getApplication())
        mutableState.update { it.copy(busy = true, preparationError = null,
            roadReport = RoadBatchReport(plan.scope, RoadBatchPhase.RUNNING, plan.candidateCount, plan.requests.size),
            roadEvidence = null, roadGraph = null, routeResult = null) }
        roadJob = viewModelScope.launch {
            val observations = mutableListOf<CandidateRoadEvidence>()
            var storedPoints = 0
            try {
                // A process death must never resurrect the previous attempt as the current completed result.
                store.saveRoadReport(state.value.roadReport!!.copy(phase = RoadBatchPhase.INTERRUPTED))
                withTimeout(120_000) {
                    RoadBatchRunner.run(plan, AmapDistance(), probe = { request ->
                        try {
                            RoadLinkOutcome.Received(AmapWalkingRoadSource(getApplication()).probe(request, true, lifecycle))
                        } catch (e: RoadProbeException) {
                            RoadLinkOutcome.Failed(when (e.reason) {
                                RoadProbeFailure.EMPTY_RESULT -> RoadLinkFailure.NO_ROUTE
                                RoadProbeFailure.AUTHORIZATION -> RoadLinkFailure.AUTHORIZATION
                                RoadProbeFailure.QUOTA -> RoadLinkFailure.QUOTA
                                RoadProbeFailure.NETWORK -> RoadLinkFailure.NETWORK
                                RoadProbeFailure.MISMATCHED_QUERY, RoadProbeFailure.TOO_COMPLEX -> RoadLinkFailure.INVALID_REPLY
                                else -> RoadLinkFailure.SDK_ERROR
                            })
                        }
                    }, publish = { report ->
                        if (state.value.connectionEpoch == snapshot.connectionEpoch && state.value.config == config)
                            mutableState.update { it.copy(roadReport = report) }
                    }, observe = { request, leg ->
                        val count = leg.steps.sumOf { it.size }
                        if (storedPoints + count <= RoadEvidenceCodec.MAX_POINTS &&
                            state.value.connectionEpoch == snapshot.connectionEpoch && state.value.config == config) {
                            observations.add(CandidateRoadEvidence(request.fromId, request.toId,
                                WalkingLeg(leg.distanceMeters, leg.steps.map { it.toList() })))
                            storedPoints += count
                            val evidence = RoadEvidenceSnapshot(plan.scope, observations.toList())
                            mutableState.update { it.copy(roadEvidence = evidence) }
                        }
                    })
                }
            } catch (_: TimeoutCancellationException) {
                mutableState.update { current ->
                    if (current.roadReport?.scope == plan.scope) current.copy(
                        roadReport = current.roadReport.copy(phase = RoadBatchPhase.FAILED)) else current
                }
            } catch (_: CancellationException) {
                mutableState.update { current ->
                    if (current.roadReport?.scope == plan.scope) current.copy(
                        roadReport = current.roadReport.copy(phase = RoadBatchPhase.INTERRUPTED)) else current
                }
            } catch (_: Exception) {
                mutableState.update { current ->
                    if (current.roadReport?.scope == plan.scope) current.copy(
                        roadReport = current.roadReport.copy(phase = RoadBatchPhase.FAILED)) else current
                }
            } finally {
                var report = state.value.roadReport?.takeIf { it.scope == plan.scope }
                val evidence = state.value.roadEvidence?.takeIf { it.scope == plan.scope }
                if (report?.phase == RoadBatchPhase.COMPLETE &&
                    !io.github.ibudaoa.domain.PreparationRoads.ready(config, snapshot.connectionEpoch, report, evidence)) {
                    report = report?.copy(phase = RoadBatchPhase.FAILED)
                    mutableState.update { it.copy(roadReport = report) }
                }
                val finalReport = report
                withContext(NonCancellable) {
                    if (finalReport != null) runCatching { store.saveRoadCheck(finalReport, evidence) }.onFailure {
                        val failedReport = finalReport.copy(phase = RoadBatchPhase.FAILED)
                        mutableState.update { it.copy(roadReport = failedReport, preparationError = "道路资料未能保存，请重试。") }
                        // Keep the safe summary if private geometry is too large or invalid.
                        mutableState.update { current ->
                            if (current.roadEvidence?.scope == plan.scope) current.copy(roadEvidence = null, roadGraph = null, routeResult = null) else current
                        }
                        runCatching { store.saveRoadReport(failedReport) }.onFailure {
                            showMessage("道路检查结果未能保存；请确认当前配置未变更。")
                        }
                    }
                }
                roadJob = null
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun cancelRoadCheck() { roadJob?.cancel() }

    /** Explicit, foreground-only checks of gaps from already cached campus candidate roads. */
    fun confirmAllRoadConnectionsAndDirections() {
        val plan = state.value.gapPlan ?: return
        operation("道路确认未能保存，原有数据已保留。") {
            store.confirmAllRoadConnectionsAndDirections(plan)
            showMessage("道路连接及往返确认已保存，请重新生成运行计划。")
        }
    }

    fun confirmAllReviewedDirections() {
        val graph = state.value.baseRoadGraph ?: return
        operation("往返道路未能保存，请重新检查当前道路。") {
            store.setAllReviewedDirections(graph.scope, graph.fingerprint)
        }
    }

    fun setReviewedDirection(pair: Pair<String, String>, confirmed: Boolean) {
        val graph = state.value.baseRoadGraph ?: return
        operation("往返道路未能保存，请重新检查当前道路。") {
            store.setReviewedDirection(graph.scope, graph.fingerprint, pair, confirmed)
        }
    }

    fun setManualGap(index: Int, confirmed: Boolean) {
        val plan = state.value.gapPlan ?: return
        operation("确认未能保存，请重新检查当前道路。") { store.setManualGap(plan, index, confirmed) }
    }

    fun checkRoadGaps(lifecycle: Lifecycle) {
        val snapshot = state.value
        val config = snapshot.config ?: return
        val evidence = snapshot.roadEvidence ?: return showMessage("没有可用的已保存道路资料。")
        if (snapshot.busy || roadJob != null || !snapshot.capturedConfig || !snapshot.mapConsent ||
            !BuildConfig.MAP_KEY_CONFIGURED || snapshot.capture.active ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        mutableState.update { it.copy(busy = true, gapChecking = true, gapReport = null) }
        roadJob = viewModelScope.launch {
            var report: RoadGapReport? = null
            try {
                val plan = withContext(Dispatchers.Default) { RoadGapPlan.create(config, evidence, AmapDistance()) }
                if (state.value.config != config || state.value.roadEvidence != evidence) return@launch
                val saved = state.value.gapEvidence
                val records = saved?.records?.toMutableList() ?: mutableListOf()
                check(saved == null || (saved.scope == plan.scope && saved.evidenceFingerprint == plan.evidenceFingerprint &&
                    records.map { it.gap } == plan.gaps.take(records.size)))
                report = RoadGapReport(plan.scope, plan.evidenceFingerprint, plan.eligibleGapCount, plan.gaps.size, records.map { it.outcome }, false)
                mutableState.update { it.copy(gapReport = report) }
                if (records.size == plan.gaps.size) {
                    showMessage(if (plan.gaps.isEmpty()) "没有符合本次检查条件的道路间隔。" else "已完成可检查的道路间隔，请导出结果。")
                    return@launch
                }
                val source = AmapWalkingRoadSource(getApplication())
                withTimeout(120_000) {
                    for ((index, gap) in plan.gaps.withIndex().drop(records.size)) {
                        if (index > 0) delay(1_000)
                        var bridge: WalkingLeg? = null
                        val outcome = try {
                            val paths = source.probeGap(gap, true, lifecycle)
                            val result = withContext(Dispatchers.Default) { RoadGapReviewer.review(gap, paths) }
                            if (result == GapCheckOutcome.EXACT_CONNECTED) {
                                val candidate = paths.first()
                                val used = records.sumOf { it.bridge?.steps?.sumOf { step -> step.size } ?: 0 }
                                if (candidate.steps.size <= 2048 && used + candidate.steps.sumOf { it.size } <= 10_000) {
                                    bridge = candidate
                                    result
                                } else GapCheckOutcome.INVALID_GEOMETRY
                            } else result
                        } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); GapCheckOutcome.TIMEOUT }
                        catch (e: CancellationException) { throw e }
                        catch (e: RoadProbeException) {
                            if (e.reason == RoadProbeFailure.EMPTY_RESULT) GapCheckOutcome.NO_ROUTE else GapCheckOutcome.SERVICE_ERROR
                        }
                        if (outcome in setOf(GapCheckOutcome.SERVICE_ERROR, GapCheckOutcome.TIMEOUT)) {
                            report = report!!.copy(outcomes = report!!.outcomes + outcome, interrupted = true)
                            break
                        }
                        val record = RoadGapRecord(gap, outcome, bridge)
                        val updated = RoadGapEvidence(plan.scope, plan.evidenceFingerprint, records + record)
                        withContext(NonCancellable) { store.saveGapEvidence(updated) }
                        records.add(record)
                        report = report!!.copy(outcomes = records.map { it.outcome })
                        if (state.value.config == config && state.value.roadEvidence == evidence)
                            mutableState.update { it.copy(gapReport = report, gapEvidence = updated) }
                    }
                }
                showMessage("道路间隔检查结束，请导出脱敏配置。")
            } catch (_: TimeoutCancellationException) {
                report = report?.copy(interrupted = true)
            } catch (_: CancellationException) {
                report = report?.copy(interrupted = true)
            } catch (_: Exception) {
                report = report?.copy(interrupted = true)
                showMessage("道路间隔检查未完成，请导出已有结果。")
            } finally {
                if (state.value.config == config && state.value.roadEvidence == evidence)
                    mutableState.update { it.copy(gapReport = report, gapChecking = false, busy = false) }
                else mutableState.update { it.copy(gapChecking = false, busy = false) }
                roadJob = null
            }
        }
    }

    fun refreshCertificateStatus() {
        if (state.value.busy || certificateCheckJob?.isActive == true) return
        val generation = certificateGate.current()
        val previousPhase = state.value.certificateSetup
        if (previousPhase in setOf(CertificateSetupPhase.WAITING_INSTALL, CertificateSetupPhase.NOT_INSTALLED))
            mutableState.update { it.copy(certificateSetup = CertificateSetupPhase.CHECKING) }
        certificateCheckJob = viewModelScope.launch {
            val trusted = try { withContext(Dispatchers.IO) { certificates.isInstalled() } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (generation == certificateGate.current()) mutableState.update { it.copy(certificateSetup = CertificateSetupPhase.CHECK_FAILED) }
                return@launch
            }
            val file = if (!trusted) try {
                withContext(Dispatchers.IO) { certificateDownloads.existing(certificates.root().encoded) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { null } else null
            if (generation != certificateGate.current()) return@launch
            val phase = when {
                trusted -> CertificateSetupPhase.INSTALLED
                file != null && certificateGate.accepts(generation) -> CertificateSetupPhase.NOT_INSTALLED
                file != null -> CertificateSetupPhase.WAITING_INSTALL
                else -> CertificateSetupPhase.IDLE
            }
            mutableState.update { it.copy(certificateTrusted = trusted, certificateFileName = file?.fileName, certificateSetup = phase) }
            if (captureFlowActive && !state.value.capture.active && certificateGate.returned(generation, trusted)) {
                sendCaptureAction(CaptureAction.REQUEST_VPN)
            }
        }
    }

    fun cancelCapture() {
        if(requestedCapturePurpose==CapturePurpose.ACCOUNT_SESSION && state.value.capture.active)
            AccountSessionRuntime.session.cancelConnection()
        roadJob?.cancel()
        captureFlowActive = false
        CaptureVpnService.stopOwned(getApplication())
        certificateGate.cancel()
        certificateCheckJob?.cancel()
        mutableState.update { it.copy(certificateSetup = if (it.certificateTrusted) CertificateSetupPhase.INSTALLED else CertificateSetupPhase.IDLE) }
        while (actions.tryReceive().isSuccess) Unit
    }

    private suspend fun sendCaptureAction(action: CaptureAction) {
        if (captureFlowActive) actions.send(action)
    }

    fun markOfficialOpened() {
        val generation = state.value.capture.generation
        CaptureRuntime.update(generation) { it.copy(officialOpened = true) }
    }

    fun importConfig(uri: Uri) = operation {
        check(!state.value.capture.active)
        val config = withContext(Dispatchers.IO) {
            val input = getApplication<Application>().contentResolver.openInputStream(uri)
                ?: error("No input stream")
            val bytes = input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > DiagnosticCodec.MAX_BYTES) {
                        throw DiagnosticException(DiagnosticError.TOO_LARGE)
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            DiagnosticCodec.decode(bytes.toString(Charsets.UTF_8))
        }
        store.save(config)
        messages.send("已导入脱敏配置，仅供诊断；账号尚未连接，不能开始任务。")
    }

    fun exportConfig(uri: Uri) {
        if (!state.value.diagnosticMode) return showMessage("请先在设置中开启调试模式。")
        val snapshot = state.value.config ?: return
        val automatic=state.value.automaticRun
        val requirements=state.value.executionRequirements
        val report = state.value.roadReport
        val epoch = state.value.connectionEpoch
        val captureRevision = state.value.captureRevision
        val capturedAt = state.value.capturedAtEpochSecond
        val evidence = state.value.roadEvidence
        val gapReport = state.value.gapReport
        val gapEvidence = state.value.gapEvidence
        val manualGaps = state.value.manualGaps
        val reviewedDirections = state.value.reviewedDirections
        val previewSummary = state.value.previewSummary
        val locationDiagnostics = state.value.locationDiagnostics
        val accountCheck = state.value.capture
        val accountSession=state.value.accountSession
        val runSummary=state.value.runningPlanSummary
        operation {
            val (graph, effectiveGapReport) = if (evidence != null && report?.phase != RoadBatchPhase.RUNNING) withContext(Dispatchers.Default) {
                val metric = AmapDistance()
                val base = runCatching { ObservedRoadGraphBuilder.build(snapshot, evidence, metric, gapEvidence, manualGaps, automatic = true) }.getOrNull()
                val graph = base
                val savedReport = gapEvidence?.let { saved -> runCatching {
                    val plan = RoadGapPlan.create(snapshot, evidence, metric)
                    check(saved.scope == plan.scope && saved.evidenceFingerprint == plan.evidenceFingerprint &&
                        saved.records.map { it.gap } == plan.gaps.take(saved.records.size))
                    RoadGapReport(plan.scope, plan.evidenceFingerprint, plan.eligibleGapCount, plan.gaps.size,
                        saved.records.map { it.outcome }, saved.records.size < plan.gaps.size)
                }.getOrNull() }
                graph to (gapReport ?: savedReport)
            } else null to null
            withContext(Dispatchers.IO) {
                val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                    ?: error("No output stream")
                output.bufferedWriter(Charsets.UTF_8).use {
                    it.write(PreparationExport.encode(snapshot, epoch, captureRevision, report, capturedAt, graph, effectiveGapReport, evidence, AmapDistance(), previewSummary, locationDiagnostics, accountCheck,accountSession,runSummary,null,requirements,automatic))
                }
            }
            messages.send("脱敏配置已导出，未知字段与账号凭证未包含在文件中。")
        }
    }

    fun exportConnectionReport(uri: Uri) {
        val snapshot = state.value
        operation {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                    ?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(ConnectionReport.encode(snapshot.capture, snapshot.certificateTrusted, snapshot.capturedConfig,snapshot.accountSession))
                    } ?: error("No output stream")
            }
            messages.send("连接诊断已导出，不含凭证、原始请求或响应。")
        }
    }

    fun clearDiagnostic() = operation("当前自动任务尚未核对，不能清除配置。") {
        check(!automaticTaskOpen())
        withContext(Dispatchers.IO) { AccountSessionRuntime.initialize(getApplication());AccountSessionRuntime.session.clear() }
        check(!state.value.capture.active)
        store.clear()
        messages.send("已清除本地诊断配置。")
    }

    fun onForegroundLost() {
        updateClient?.close();updateJob?.cancel()
        accountForeground=false
        interruptAutomatic()
        runPreparationJob?.cancel();preparingRun=false;runGrant=null;preparedRunningPlan=null
        accountResumeJob?.cancel()
        AccountSessionRuntime.session.onBackground()
        routeRefreshJob?.cancel()
        previewJob?.cancel()
        mutableState.update { it.copy(session = it.session.onForegroundLost(), displayFix=null, localPreview=null,
            runningPlanSummary=if(it.runningPlanSummary.phase in setOf(RunningPlanPhase.IDLE,RunningPlanPhase.BLOCKED,RunningPlanPhase.INTERRUPTED))it.runningPlanSummary
                else it.runningPlanSummary.copy(phase=RunningPlanPhase.INTERRUPTED,problem=RunningPlanProblem.CANCELLED)) }
    }

    fun refreshRouteDraft() {
        if (routeRefreshJob?.isActive == true) return
        val snapshot = state.value
        val config = snapshot.config ?: return
        val graph = snapshot.roadGraph ?: return
        val cache = snapshot.pointCache ?: return
        routeRefreshJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val metric = AmapDistance()
                runCatching { RouteDraftBuilder.build(config, snapshot.capturedConfig, cache, graph,
                    metric.implementationId, Instant.now(), ZoneId.of("Asia/Shanghai"), metric) }.getOrNull()
            }
            mutableState.update { current ->
                if (current.config == config && current.connectionEpoch == snapshot.connectionEpoch &&
                    current.capturedConfig == snapshot.capturedConfig && current.pointCache == cache &&
                    current.roadGraph === graph && current.roadReport?.phase != RoadBatchPhase.RUNNING)
                    current.copy(routeResult = result) else current
            }
        }
    }

    fun onForegroundReturned() {
        if(io.github.ibudaoa.capture.CaptureReturnPolicy.shouldRelease(CaptureRuntime.state.value))CaptureVpnService.finishOnReturn(getApplication())
        accountForeground=true
        resumeAccount()
        mutableState.update { it.copy(session = it.session.onForegroundReturned()) }
    }

    private var updateJob:Job?=null
    private var updateClient:io.github.ibudaoa.updates.ReleaseUpdateClient?=null
    fun checkUpdates() {
        if(state.value.automaticRun.blocksExternalUi)return showMessage("请等待本次执行结束后再检查更新。")
        if(updateJob?.isActive==true)return
        val repository=io.github.ibudaoa.updates.ReleaseRepository.parse(io.github.ibudaoa.BuildConfig.REPOSITORY_URL)
        if(repository==null) {
            mutableState.update { it.copy(updateCheck=io.github.ibudaoa.updates.UpdateSummary(io.github.ibudaoa.updates.UpdatePhase.NOT_CONFIGURED)) }
            return showMessage("项目仓库和更新源尚未配置。")
        }
        updateJob=viewModelScope.launch {
            val client=io.github.ibudaoa.updates.ReleaseUpdateClient();updateClient=client
            mutableState.update { it.copy(updateCheck=io.github.ibudaoa.updates.UpdateSummary(io.github.ibudaoa.updates.UpdatePhase.CHECKING)) }
            try {
                val result=withContext(Dispatchers.IO) { client.check(repository,io.github.ibudaoa.BuildConfig.VERSION_NAME) }
                if(accountForeground)mutableState.update { it.copy(updateCheck=result) }
            } catch(e:CancellationException) {
                mutableState.update { it.copy(updateCheck=io.github.ibudaoa.updates.UpdateSummary(io.github.ibudaoa.updates.UpdatePhase.INTERRUPTED)) }
                throw e
            } finally { client.close();if(updateClient===client)updateClient=null }
        }
    }

    fun showMessage(message: String) { viewModelScope.launch { messages.send(message) } }

    private fun operation(failureMessage: String = "操作未完成，请检查文件访问权限后重试。", block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: DiagnosticException) { messages.send(e.reason.userMessage) }
            catch (_: Exception) {
                // Never echo exception messages: providers may include private URIs or input.
                messages.send(failureMessage)
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}

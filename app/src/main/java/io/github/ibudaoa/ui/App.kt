package io.github.ibudaoa.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import io.github.ibudaoa.maps.CampusMap
import io.github.ibudaoa.maps.DisplayFix
import io.github.ibudaoa.maps.LocationDiagnostics
import io.github.ibudaoa.preview.PreviewPhase
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ibudaoa.AppState
import io.github.ibudaoa.capture.CapturePhase
import io.github.ibudaoa.capture.CapturePurpose
import io.github.ibudaoa.capture.AccountReadOutcome
import io.github.ibudaoa.BuildConfig
import io.github.ibudaoa.diagnostics.StageVerification
import io.github.ibudaoa.domain.ConfigurationReviewer
import io.github.ibudaoa.domain.CalendarReviewer
import io.github.ibudaoa.domain.PreparationStep
import io.github.ibudaoa.domain.PreparationStepResolver
import io.github.ibudaoa.data.DiagnosticStore
import io.github.ibudaoa.roads.RoadBatchPhase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.Flow

internal enum class Destination(val label: String, val icon: ImageVector) {
    RUN("跑步", Icons.AutoMirrored.Filled.DirectionsRun),
    SCHOOL("学校", Icons.Default.School),
    SETTINGS("设置", Icons.Default.Settings),
}

@Composable
fun IbudaoaApp(
    state: AppState,
    events: Flow<String>,
    onAcknowledgePreparation: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onExportConnectionReport: () -> Unit,
    onClear: () -> Unit,
    onMessage: (String) -> Unit,
    onCancelCapture: () -> Unit,
    onMapConsent: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onAccountReadCheck: () -> Unit,
    onVerifyAccountSession: () -> Unit,
    onDisconnectAccountSession: () -> Unit,
    onCheckRoadGaps: () -> Unit,
    onSetManualGap: (Int, Boolean) -> Unit,
    onSetReviewedDirection: (Pair<String, String>, Boolean) -> Unit,
    onConfirmAllDirections: () -> Unit,
    onConfirmAllRoadConnections: () -> Unit,
    onDisplayFix: (DisplayFix?) -> Unit,
    onLocationDiagnostics: (LocationDiagnostics) -> Unit,
    onPrepareLocalPreview: () -> Unit,
    onPrepareRunningPlan: () -> Unit,
    onAutomaticAction: () -> Unit,
    onFinishAutomatic: () -> Unit,
    onArchiveAutomatic: () -> Unit,
    onCheckUpdates: () -> Unit,
    onDiagnosticMode: (Boolean) -> Unit,
    onConfirmPreviewOrigin: () -> Unit,
    onClosePreviewReview: () -> Unit,
) {
    var showAccountCheck by rememberSaveable { mutableStateOf(false) }
    var showRoadDirections by rememberSaveable { mutableStateOf(false) }
    var directionIndex by rememberSaveable { mutableStateOf(0) }
    var showManualGaps by rememberSaveable { mutableStateOf(false) }
    var manualIndex by rememberSaveable { mutableStateOf(0) }
    var destination by rememberSaveable { mutableStateOf(Destination.RUN) }
    var showWarmup by rememberSaveable { mutableStateOf(true) }
    var showSummary by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var showMapPrivacy by rememberSaveable { mutableStateOf(false) }
    var showGapCheck by rememberSaveable { mutableStateOf(false) }
    val reviewingOrigin = state.localPreview != null && state.previewSummary?.phase == PreviewPhase.REVIEW_ORIGIN
    val uriHandler = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(events) { events.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.loaded) { if(state.loaded && state.capturedConfig) showWarmup=false }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AppNavigation(destination) { destination = it }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!state.loaded) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else when (destination) {
                Destination.RUN, Destination.SCHOOL -> MapPage(
                    state = state,
                    runningPage = destination == Destination.RUN,
                    onWarmup = { showWarmup = true },
                    onImport = onImport,
                    onExport = onExport,
                    onExportConnectionReport = onExportConnectionReport,
                    onSummary = { showSummary = true },
                    onClear = { confirmClear = true },
                    onCheckRoadGaps = { showGapCheck = true },
                    onReviewRoadGaps = { manualIndex = 0; showManualGaps = true },
                    onReviewRoadDirections = { directionIndex = 0; showRoadDirections = true },
                    onDisplayFix = onDisplayFix,
                    onLocationDiagnostics = onLocationDiagnostics,
                    onPrepareLocalPreview = onPrepareLocalPreview,
                    onPrepareRunningPlan = onPrepareRunningPlan,
                    onAutomaticAction = onAutomaticAction,
                    onFinishAutomatic = onFinishAutomatic,
                    onArchiveAutomatic = onArchiveAutomatic,
                    mapActive = !showWarmup && !showSummary && !confirmClear && !showMapPrivacy && !showGapCheck && !showManualGaps && !showRoadDirections && !showAccountCheck && !reviewingOrigin && !state.capture.active,
                )
                Destination.SETTINGS -> SettingsPage(
                    state = state,
                    onExit = { onCancelCapture(); onClear() },
                    onConnect = { showWarmup = true; onReconnect() },
                    onAccountCheck = { showWarmup=false;showAccountCheck=true },
                    onMessage = onMessage,
                    onDisableMap = { onMapConsent(false) }, onCheckUpdates=onCheckUpdates, onDiagnosticMode=onDiagnosticMode,
                )
            }
        }
    }

    if (showAccountCheck) {
        val check=state.capture.takeIf { it.purpose==CapturePurpose.ACCOUNT_SESSION }
        val done=state.accountSession.available || state.accountSession.credentialsPersisted || check?.phase in setOf(CapturePhase.CAPTURED,CapturePhase.ERROR,CapturePhase.CANCELLED)
        AlertDialog(onDismissRequest={ onCancelCapture();showAccountCheck=false },
            title={ Text("账号连接") },
            text={ Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(if (check?.active==true) check.message
                    else "首次或补齐维护材料时，官方先打开“我的”，再进入校园跑准备页，不点击开始。材料加密保存在本机，以后自动恢复和核验。")
                Text("切换应用、导出或重启会保留登录，返回前台后重新核验。临时连接最多两分钟，学校配置和道路确认保持，不启动运动。")
                Text(state.accountSession.label)
                Text(state.accountSession.preparation.label)
                Text(when(state.accountSession.refreshState) {
                    io.github.ibudaoa.capture.AccountRefreshState.IN_FLIGHT -> "续期结果尚未确认，已停止自动重复请求。"
                    io.github.ibudaoa.capture.AccountRefreshState.UNVERIFIED -> "更新的登录已保存，等待核验。"
                    io.github.ibudaoa.capture.AccountRefreshState.AVAILABLE -> "已保存续期材料，失效时有界自动续期。"
                    io.github.ibudaoa.capture.AccountRefreshState.MISSING -> "当前尚无续期材料；已有有效登录仍保留。"
                })
                state.accountSession.lastRead?.let { Text(it.outcome.label) }
                if(state.accountSession.available || state.accountSession.phase==io.github.ibudaoa.capture.AccountSessionPhase.STORAGE_ERROR)
                    TextButton(onClick=onVerifyAccountSession,enabled=!state.busy) { Text("核对登录和跑前条件") }
                if(state.accountSession.credentialsPersisted || state.accountSession.available) TextButton(onClick=onDisconnectAccountSession,enabled=!state.busy) { Text("退出已保存账号") }
                if (done) TextButton(onClick=onAccountReadCheck,enabled=!state.busy) { Text("重新连接账号") }
                check?.accountRead?.let { result ->
                    Text(if (result.outcome==AccountReadOutcome.PASSED) "一次独立只读请求已通过。"
                        else "${result.outcome.label}，请导出交回核对。")
                }
            } },
            confirmButton={ TextButton(onClick={ if (check?.active==true || !done) onAccountReadCheck() else onExport() },
                enabled=!state.busy && check?.phase!=CapturePhase.STARTING) {
                Text(when { check?.active==true -> "返回官方应用"; done -> "导出会话结果"; else -> "连接账号" })
            } },
            dismissButton={ TextButton(onClick={ onCancelCapture();showAccountCheck=false }) { Text("关闭") } })
    }

    val originPreview = state.localPreview
    if (reviewingOrigin && originPreview != null && state.config != null) {
        Dialog(onDismissRequest = onClosePreviewReview, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                CampusMap(state.config,state.mapConsent,true,state.roadEvidence,null,
                    modifier=Modifier.fillMaxSize(),originProposal=originPreview.proposal,
                    selectedIds=originPreview.points.selectedIds,onFix=onDisplayFix,onLocationDiagnostics=onLocationDiagnostics)
                Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(),color=MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.statusBarsPadding().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("核对预览起点",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        TextButton(onClick=onClosePreviewReview) { Text("取消") }
                    }
                }
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(),color=MaterialTheme.colorScheme.surfaceContainer,
                    shape=RoundedCornerShape(topStart=20.dp,topEnd=20.dp)) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("请确认起点位置正确，橙色接入建议可以到达参考路线。")
                        Text("若位置不对或连接不能通过，请取消并反馈，不要确认。")
                        Text("接入参考约 ${kotlin.math.ceil(originPreview.proposal.gapMeters).toInt()} 米，已计入计划总距离；拟定 ${originPreview.points.selectedIds.size} 个点。尚未开始运动。")
                        Text("程序从确认的当前位置沿本次固定路线自动推进，接入段计入总距离；无需本人走跑。")
                        Button(onClick=onConfirmPreviewOrigin,enabled=!state.busy && state.displayFix != null,
                            modifier=Modifier.fillMaxWidth()) { Text(if (state.busy) "正在计算路线…" else "确认位置与连接，生成草稿") }
                    }
                }
            }
        }
    }

    val reviewedRoads = state.baseRoadGraph?.edges.orEmpty()
    if (showRoadDirections && reviewedRoads.isNotEmpty() && state.config != null) {
        val index = directionIndex.coerceIn(0, reviewedRoads.lastIndex)
        val road = reviewedRoads[index]
        val pair = road.fromId to road.toId
        val confirmed = state.reviewedDirections?.reversible?.contains(pair) == true
        Dialog(onDismissRequest = { showRoadDirections = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                CampusMap(state.config, state.mapConsent, false, state.roadEvidence, null,
                    modifier = Modifier.fillMaxSize(), reviewRoad = road)
                Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("确认往返道路  ${index + 1}/${reviewedRoads.size}", Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { showRoadDirections = false }) { Text("完成") }
                    }
                }
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (confirmed) "已确认可以往返" else "尚未确认往返", style = MaterialTheme.typography.titleMedium)
                        Text("橙色是这一段道路。确认整段都可以双向通过后，可用于往返规划；不确定时请跳过。")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { directionIndex = index - 1 }, enabled = index > 0 && !state.busy) { Text("上一条") }
                            TextButton(onClick = { directionIndex = index + 1 }, enabled = index < reviewedRoads.lastIndex && !state.busy) { Text("下一条") }
                        }
                        TextButton(onClick = onConfirmAllDirections, enabled = !state.busy &&
                            (state.reviewedDirections?.reversible?.size ?: 0) < reviewedRoads.size,
                            modifier = Modifier.fillMaxWidth()) { Text("全部确认可以往返") }
                        Button(onClick = { onSetReviewedDirection(pair, !confirmed) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()) { Text(if (confirmed) "撤销往返确认" else "确认整段可以往返") }
                    }
                }
            }
        }
    } else if (showRoadDirections) {
        AlertDialog(onDismissRequest = { showRoadDirections = false }, title = { Text("道路资料不可用") },
            text = { Text("当前没有可确认的连续道路，请返回后检查道路资料。") },
            confirmButton = { TextButton(onClick = { showRoadDirections = false }) { Text("关闭") } })
    }

    val reviewPlan = state.gapPlan
    if (showManualGaps && reviewPlan != null && reviewPlan.gaps.isNotEmpty() && state.config != null) {
        val index = manualIndex.coerceIn(0, reviewPlan.gaps.lastIndex)
        val gap = reviewPlan.gaps[index]
        val confirmed = state.manualGaps?.takeIf { it.evidenceFingerprint == reviewPlan.evidenceFingerprint }
            ?.indices?.contains(index) == true
        Dialog(onDismissRequest = { showManualGaps = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                CampusMap(state.config, state.mapConsent, false, state.roadEvidence, null,
                    modifier = Modifier.fillMaxSize(), reviewGap = gap)
                Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("确认道路连接  ${index + 1}/${reviewPlan.gaps.size}", Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { showManualGaps = false }) { Text("完成") }
                    }
                }
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (confirmed) "已确认连接" else "尚未确认", style = MaterialTheme.typography.titleMedium)
                        Text("橙线是待确认的连接，请放大地图查看两侧道路；确认这里确实可以通过后再保存。")
                        if (state.reviewedDirections?.reversible?.isNotEmpty() == true)
                            Text("如需修改连接，请先撤销已确认的往返道路。")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { manualIndex = index - 1 }, enabled = index > 0 && !state.busy) { Text("上一处") }
                            TextButton(onClick = { manualIndex = index + 1 }, enabled = index < reviewPlan.gaps.lastIndex && !state.busy) { Text("下一处") }
                        }
                        if (reviewPlan.gaps.size == reviewPlan.eligibleGapCount) {
                            Text("若此前已核对当前全部${reviewPlan.gaps.size}处连接，且道路均可往返，可一次保存全部确认。仅作用于本次显示的道路。")
                            TextButton(onClick = onConfirmAllRoadConnections, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth()) { Text("确认全部连接并可往返") }
                        }
                        Button(onClick = { onSetManualGap(index, !confirmed) }, enabled = !state.busy && state.reviewedDirections?.reversible.isNullOrEmpty(),
                            modifier = Modifier.fillMaxWidth()) { Text(if (confirmed) "撤销确认" else "确认这里可以连接") }
                    }
                }
            }
        }
    } else if (showManualGaps) {
        AlertDialog(onDismissRequest = { showManualGaps = false }, title = { Text("道路资料不可用") },
            text = { Text("当前没有可确认的道路间隔，请返回后检查道路资料。") },
            confirmButton = { TextButton(onClick = { showManualGaps = false }) { Text("关闭") } })
    }

    if (showGapCheck) {
        AlertDialog(onDismissRequest = { showGapCheck = false },
            title = { Text("检查道路间隔") },
            text = { Text("从已保存的校园候选道路中选取最多 24 处间隔，向高德查询连接道路。每次检查最多两分钟，已检查的结果会保存；请保持应用前台。不使用当前位置，也不会开始运动。导出仅含数量。") },
            confirmButton = { TextButton(onClick = { showGapCheck = false; onCheckRoadGaps() }) { Text("开始检查") } },
            dismissButton = { TextButton(onClick = { showGapCheck = false }) { Text("取消") } })
    }

    if (state.loaded && showWarmup) {
        WarmupDialog(
            state = state,
            onContinue = {
                when (PreparationStepResolver.next(state.capturedConfig, state.captureRevision,
                    DiagnosticStore.CURRENT_CAPTURE_REVISION, state.capture.active, BuildConfig.MAP_KEY_CONFIGURED,
                    state.mapConsent, state.roadReport?.phase, state.busy,
                    state.roadReport?.reviewRevision ?: io.github.ibudaoa.roads.RoadBatchReport.CURRENT_REVIEW_REVISION)) {
                    PreparationStep.WAIT -> Unit
                    PreparationStep.EXPORT_RESULT -> onExport()
                    PreparationStep.MAP_UNAVAILABLE -> onMessage("学校配置已保存。地图服务尚未配置，暂不能完成运行准备；无需重新采集。")
                    PreparationStep.MAP_CONSENT -> { showWarmup = false; showMapPrivacy = true }
                    else -> onAcknowledgePreparation()
                }
            },
            onDismiss = { onCancelCapture(); showWarmup = false },
        )
    }
    if (showMapPrivacy) {
        AlertDialog(
            onDismissRequest = { showMapPrivacy = false },
            title = { Text("启用地图与前台定位") },
            text = {
                Column {
                    Text("学校地图和起点定位使用高德 SDK，联网处理位置、设备和网络信息，仅在地图页前台请求定位。接入路线查询会将起点发送给高德。点击开始自动运行后，程序会向当前账号的运动平台提交生成的轨迹，其中包括确认的起点和路径。个人位置与完整轨迹不写入诊断导出或普通日志。拒绝仍可浏览配置。")
                    TextButton(onClick = {
                        runCatching { uriHandler.openUri("https://lbs.amap.com/pages/privacy/") }
                            .onFailure { onMessage("无法打开高德隐私政策，请检查浏览器。") }
                    }) { Text("查看高德隐私政策") }
                }
            },
            confirmButton = { TextButton(onClick = { showMapPrivacy = false; showWarmup = true; onMapConsent(true) }) { Text("同意并启用") } },
            dismissButton = { TextButton(onClick = { showMapPrivacy = false }) { Text("暂不启用") } },
        )
    }
    if (showSummary) {
        val config = state.config
        val review = remember(config) { config?.let(ConfigurationReviewer::review) }
        val calendar = remember(config) { config?.let {
            CalendarReviewer.review(it, Instant.now(), ZoneId.of("Asia/Shanghai"))
        } }
        AlertDialog(
            onDismissRequest = { showSummary = false },
            title = { Text("诊断配置摘要") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (config == null) "尚未取得配置。" else if (state.capturedConfig) "来源：官方响应采集，规则与道路仍待核对" else "来源：本地导入，尚未验证")
                    state.locationDiagnostics?.let { check ->
                        Text("前台定位：${check.label}；收到 ${check.callbacks} 次回调，通过 ${check.accepted} 次。")
                    }
                    if (config != null) {
                        Text("时段规则：${config.ruleCount} 组\n候选点：${config.candidateCount} 个")
                        state.routeResult?.let { route ->
                            when (route) {
                                is io.github.ibudaoa.roads.RoutePlanResult.Draft -> Text("路线草稿约 ${String.format(Locale.ROOT, "%.2f", route.route.upperDistanceMeters / 1000)} km；" +
                                    (if (route.route.includesManualRoadConfirmation) "包含手动确认的道路连接；" else "") + "平台状态仍待核对。")
                                is io.github.ibudaoa.roads.RoutePlanResult.Blocked -> Text(when (route.problem) {
                                    io.github.ibudaoa.roads.RoutePlanProblem.START_NOT_ON_ROAD -> "当前位置到道路的连接尚未确认。"
                                    io.github.ibudaoa.roads.RoutePlanProblem.ACTIVE_PLAN_REUSE_REQUIRED -> "已有任务路线需先核对并恢复。"
                                    io.github.ibudaoa.roads.RoutePlanProblem.NO_OBSERVED_ROUTE -> "现有道路资料尚不能连接本次全部点位。"
                                    else -> "路线条件尚未全部确认，继续保留本次点位。"
                                })
                            }
                        }
                        state.roadGraph?.let { graph ->
                            Text("道路资料：${graph.edges.size} 条连续连接；${graph.unjoinedLegs.size} 条有未解决间隔，${graph.offsetLegs.size} 条道路端点与打卡点中心不同。仅用于规划检查。")
                            Text("已用现有道路片段或手动确认接通 ${graph.locallyResolvedGaps} 处间隔；其中手动确认 ${graph.manualConfirmationCount} 处。")
                            if (graph.localResolutionLimited) Text("本地道路分析达到计算上限，剩余连接尚未确认。")
                            graph.endpointProximity?.let { proximity ->
                                val count = proximity.values.count { it.startWithinRadius && it.endWithinRadius }
                                Text("${count}/${proximity.size} 条道路的两个端点位于客户端打卡距离内；道路连续性和平台判定仍需核对。")
                            }
                            graph.topology?.let { topology ->
                                Text("道路分段按坐标重合可归为 ${topology.weakComponents} 组；路口是否相通和行进方向仍需核对。")
                            }
                        }
                        state.roadReport?.let { roads ->
                            Text("道路检查：${roads.links.size}/${roads.plannedLinks} 段，${roads.phase.name}。" +
                                "失败 ${roads.links.count { it.failure != null }}；检查提示 ${roads.links.count { it.issues.isNotEmpty() }}；" +
                                "出配置边界 ${roads.links.count { it.leavesBoundary == true }}；不能当作完整可跑路线。")
                        }
                        Text("阶段核验：" + when (config.stageVerification) {
                            StageVerification.DISABLED -> "三个已识别开关均为关闭；其他条件仍需核对"
                            StageVerification.SKIPPED_EMPTY -> "配置为空，按当前策略暂时跳过人脸"
                            StageVerification.REQUIRED -> "要求核验，首版不支持"
                            StageVerification.UNKNOWN -> "未知，不能视为无需核验"
                        })
                        calendar?.let { dates ->
                            val display = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                                .withZone(ZoneId.of("Asia/Shanghai"))
                            Text("学期字段：" + if (dates.termStart != null && dates.termEnd != null)
                                "${display.format(dates.termStart)}—${display.format(dates.termEnd)}（上海时区）"
                                else "缺失或异常")
                            Text("按当前设备时间检查：${dates.termPosition.label}；${dates.currentTimeRule.label}。")
                            Text("平台历史响应：${dates.platformSignal.label}；不代表当前在线状态。")
                            Text("${dates.weekEvidence.label}；" + when {
                                dates.schoolDateRuleIdsEmpty -> "日期规则标识为空，不能替代完整有效日期核对。"
                                dates.schoolDateRuleIdsPresent -> "日期规则标识已提供，具体有效日期仍待核对。"
                                else -> "日期规则标识缺失，不能仅按学期字段放行。"
                            })
                        }
                        review?.rules?.forEach { rule ->
                            Text("时段 ${formatRuleTime(rule.startSecond)}—${formatRuleTime(rule.endSecond)}\n" +
                                "距离 ${rule.minimumKm}—${rule.maximumKm} km\n" +
                                "合规配速 ${formatPace(rule.fastestSecondsPerKm)}—${formatPace(rule.slowestSecondsPerKm)} /km\n" +
                                "至少打卡 ${rule.requiredCheckIns} 次；在线点 ${rule.onlinePoints}，离线点 ${rule.offlinePoints}")
                        }
                        review?.let { report ->
                            Text("可解析候选点：${report.candidates?.size ?: "待核对"}；跑区边界顶点：${report.allowedBoundary?.size ?: "待核对"}。" +
                                if (state.localPreview == null) "尚无当前本地预览点位。" else "本地拟定 ${state.localPreview.points.selectedIds.size} 个预览点位，尚未开始任务。")
                            if (!report.outsideBoundaryIds.isNullOrEmpty()) {
                                Text("${report.outsideBoundaryIds.size} 个官方候选点落在配置边界外，已保留原候选，等待规则核对。")
                            }
                            Text("仍待处理：\n" + report.issues.joinToString("\n") { it.label })
                        }
                    }
                    config?.structure?.let { structure ->
                        Text(if (structure.missingSections.isEmpty()) "关键配置段已出现，具体规则仍需核对。"
                            else "仍缺少 ${structure.missingSections.size} 个关键配置段，暂不能视为完整配置。")
                    }
                    Text("导出保留已识别字段和不含原始值的结构报告，不含登录凭证，不会创建运动任务。")
                }
            },
            confirmButton = { TextButton(onClick = { showSummary = false }) { Text("知道了") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除诊断配置？") },
            text = { Text("仅删除本应用保存的脱敏配置，不操作平台账号或运动记录。") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("清除") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
internal fun AppNavigation(destination: Destination, onSelect: (Destination) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Destination.entries.forEach { item ->
            NavigationBarItem(
                selected = destination == item,
                onClick = { onSelect(item) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
internal fun MapPage(
    state: AppState,
    runningPage: Boolean,
    onWarmup: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onExportConnectionReport: () -> Unit,
    onSummary: () -> Unit,
    onClear: () -> Unit,
    onCheckRoadGaps: () -> Unit,
    onReviewRoadGaps: () -> Unit,
    onReviewRoadDirections: () -> Unit,
    onDisplayFix: (DisplayFix?) -> Unit,
    onLocationDiagnostics: (LocationDiagnostics) -> Unit,
    onPrepareLocalPreview: () -> Unit,
    onPrepareRunningPlan: () -> Unit,
    onAutomaticAction: () -> Unit,
    onFinishAutomatic: () -> Unit,
    onArchiveAutomatic: () -> Unit,
    mapActive: Boolean,
    mapBackground: (@Composable () -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var confirmAutomaticFinish by rememberSaveable { mutableStateOf(false) }
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    if(confirmAutomaticFinish && state.automaticRun.phase in setOf(io.github.ibudaoa.running.AutomaticRunPhase.RUNNING,io.github.ibudaoa.running.AutomaticRunPhase.PAUSED)) {
        AlertDialog(onDismissRequest={confirmAutomaticFinish=false},title={Text("结束本次自动执行？")},
            text={Text("已自动执行 %.2f km，经过 %d/%d 个点。结束后将提交本次记录。".format(Locale.ROOT,state.automaticRun.meters/1000,state.automaticRun.visitedPoints,state.automaticRun.requiredPoints))},
            confirmButton={TextButton(onClick={confirmAutomaticFinish=false;onFinishAutomatic()}) { Text("结束并提交") }},
            dismissButton={TextButton(onClick={confirmAutomaticFinish=false}) { Text("返回") }})
    }
    if(confirmArchive)AlertDialog(onDismissRequest={confirmArchive=false},title={Text("归档本地待核对任务？")},
        text={Text("仅在你已核对官方客户端、确认没有对应记录时使用。原结束请求仍可能被延迟处理；归档不会删除平台记录，也不会重传原任务。本地加密日志会保留，归档后可重新准备。")},
        confirmButton={TextButton(onClick={confirmArchive=false;onArchiveAutomatic()}) { Text("已核对无记录，归档") }},
        dismissButton={TextButton(onClick={confirmArchive=false}) { Text("保留任务") }})
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.background)) {
        // The SDK is constructed only after a developer key and actual user consent exist.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            colors.primaryContainer.copy(alpha = 0.40f), colors.background, colors.secondaryContainer,
        ))))
        if (mapBackground != null) mapBackground() else CampusMap(state.config, state.mapConsent, mapActive, state.roadEvidence,
            state.automaticRoute ?: state.localPreview?.route ?: (state.routeResult as? io.github.ibudaoa.roads.RoutePlanResult.Draft)?.route,
            selectedIds=state.automaticRoute?.selectedIds ?: state.localPreview?.points?.selectedIds.orEmpty(),
            automaticDisplay=runningPage && state.automaticRun.phase!=io.github.ibudaoa.running.AutomaticRunPhase.IDLE,
            automaticPosition=state.automaticPosition.takeIf { runningPage },onFix=onDisplayFix,onLocationDiagnostics=onLocationDiagnostics)
        if (BuildConfig.MAP_KEY_CONFIGURED && state.mapConsent && state.config != null) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                colors.background.copy(alpha = 0.80f), colors.background.copy(alpha = 0.15f),
                colors.background.copy(alpha = 0.0f), colors.background.copy(alpha = 0.0f),
                colors.background.copy(alpha = 0.75f),
            ))))
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.config?.schoolName ?: "等待同步学校",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(state.config?.campusName ?: "连接官方账号后自动读取", style = MaterialTheme.typography.titleMedium)
                    }
                    Box {
                        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "更多操作") }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(text = { Text("跑前热身") }, onClick = { expanded = false; onWarmup() })
                            if (state.diagnosticMode) DropdownMenuItem(text={Text("导出诊断")},enabled=state.config!=null && !state.busy,
                                onClick={expanded=false;onExport()})
                            if (BuildConfig.DEBUG) DropdownMenuItem(text={Text("确认往返道路")},enabled=state.baseRoadGraph?.edges?.isNotEmpty()==true && !state.busy && !state.capture.active,
                                onClick={expanded=false;onReviewRoadDirections()})
                            if (BuildConfig.DEBUG) DropdownMenuItem(text={Text("手动确认道路连接")},enabled=state.gapPlan?.gaps?.isNotEmpty()==true && !state.busy && !state.capture.active,
                                onClick={expanded=false;onReviewRoadGaps()})
                            if (BuildConfig.DEBUG) {
                                DropdownMenuItem(text={ Text("本地路线预览") },
                                    enabled=state.roadGraph?.edges?.isNotEmpty() == true && state.mapConsent && !state.busy && !state.capture.active,
                                    onClick={ expanded=false; onPrepareLocalPreview() })
                                DropdownMenuItem(text = { Text("导出连接诊断") }, enabled = !state.busy,
                                    onClick = { expanded = false; onExportConnectionReport() })
                                DropdownMenuItem(text = { Text("导入诊断配置") }, enabled = !state.busy && !state.capture.active,
                                    onClick = { expanded = false; onImport() })
                                DropdownMenuItem(text = { Text("查看诊断摘要") },
                                    onClick = { expanded = false; onSummary() })
                                DropdownMenuItem(text = { Text("检查道路间隔") },
                                    enabled = state.roadEvidence != null && !state.busy && !state.capture.active,
                                    onClick = { expanded = false; onCheckRoadGaps() })
                                DropdownMenuItem(text = { Text("清除诊断配置") }, enabled = state.config != null && !state.busy && !state.capture.active,
                                    onClick = { expanded = false; onClear() })
                            }
                        }
                    }
                }
                if (state.gapChecking) Text("正在检查道路间隔 ${state.gapReport?.outcomes?.size ?: 0}/${state.gapReport?.planned ?: 0}，请保持应用前台。")
                else state.gapReport?.let { Text("道路间隔检查：${it.outcomes.size}/${it.planned}，结果可导出。") }
                    ?: state.gapEvidence?.let { Text("已保存 ${it.records.size} 处道路间隔结果，可继续检查或导出。") }
                state.previewSummary?.let { preview ->
                    Text(if (preview.phase == PreviewPhase.DRAFT) "${if (state.localPreview?.route != null) "本地草稿" else "上次本地预览"}约 ${String.format(Locale.ROOT,"%.2f",(preview.routeMeters ?: 0.0)/1000)} km · 拟定 ${preview.selectedCount} 个点"
                        else preview.pointProblem?.label ?: preview.problem?.label ?: "等待核对预览起点")
                }
                Spacer(Modifier.height(22.dp))
                if (runningPage) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Metric(if(state.runningPlanSummary.distanceMeters!=null)"计划距离" else "最低距离", state.runningPlanSummary.distanceMeters?.let { String.format(Locale.ROOT,"%.2f km",it/1000.0) } ?: state.config?.let { cfg -> ConfigurationReviewer.review(cfg).uniqueTimeRule(
                            java.time.LocalTime.now(ZoneId.of("Asia/Shanghai")).toSecondOfDay())?.let {
                                String.format(Locale.ROOT,"%.2f km",it.minimumKm)
                            } } ?: "待同步", Modifier.weight(1f))
                        Metric("目标配速",state.runningPlanSummary.paceSecondsPerKm?.let { "%d′%02d″ /km".format(it/60,it%60) } ?: "待生成", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(14.dp))
                }
                if(runningPage && state.runningPlanSummary.phase!=io.github.ibudaoa.running.RunningPlanPhase.IDLE) {
                    Text(state.runningPlanSummary.problem?.label ?: when(state.runningPlanSummary.phase) {
                        io.github.ibudaoa.running.RunningPlanPhase.CHECKING -> "正在读取最新跑前条件"
                        io.github.ibudaoa.running.RunningPlanPhase.SYNCING -> "正在同步规则并保留已确认道路"
                        io.github.ibudaoa.running.RunningPlanPhase.WAITING_LOCATION -> "等待新鲜准确的前台定位"
                        io.github.ibudaoa.running.RunningPlanPhase.CONNECTING_ROUTE -> "正在规划当前位置到参考路线的接入路段"
                        io.github.ibudaoa.running.RunningPlanPhase.REVIEW_ORIGIN -> "请核对起点到道路的连接"
                        io.github.ibudaoa.running.RunningPlanPhase.PLANNED -> "本次计划 · 预计${((state.runningPlanSummary.durationSeconds ?: 0)+59)/60}分钟"
                        else -> "计划尚未就绪"
                    })
                    if (BuildConfig.DEBUG && state.runningPlanSummary.problem == io.github.ibudaoa.running.RunningPlanProblem.ROAD_REVIEW)
                        TextButton(onClick = onReviewRoadGaps, enabled = !state.busy) { Text("核对已有道路连接") }
                    state.runningPlanSummary.accessDistanceMeters?.let { Text("自动路径包含约${it}米当前位置接入路段。") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(9.dp).background(colors.onSurfaceVariant, CircleShape))
                    Text(if (state.capturedConfig) "官方配置已采集 · 待核对" else if (state.config == null) "尚未连接官方账号" else "诊断配置 · 未经平台验证",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                if (!BuildConfig.MAP_KEY_CONFIGURED || !state.mapConsent || state.config == null) {
                    Spacer(Modifier.height(64.dp))
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Map, null, Modifier.size(56.dp), tint = colors.primary.copy(alpha = 0.5f))
                        Spacer(Modifier.height(16.dp))
                        Text(if (!BuildConfig.MAP_KEY_CONFIGURED) "地图服务待配置" else "地图尚未启用", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(if (!BuildConfig.MAP_KEY_CONFIGURED) "完成地图配置后显示校园地图" else "通过运行准备启用学校地图", textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (state.busy) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(12.dp).size(24.dp))
            }
            if (runningPage) {
                Text("自动执行时请保持应用前台，屏幕将保持常亮。", Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                val ready=state.capturedConfig && state.mapConsent && state.roadGraph!=null && !state.capture.active
                val planned=state.runningPlanSummary.phase==io.github.ibudaoa.running.RunningPlanPhase.PLANNED
                val auto=state.automaticRun
                val active=auto.phase!=io.github.ibudaoa.running.AutomaticRunPhase.IDLE
                if(active) {
                    LinearProgressIndicator(progress={auto.progressFraction},modifier=Modifier.fillMaxWidth().padding(vertical=8.dp))
                    val remaining=auto.remainingSeconds
                    Text("执行进度 %d%% · 已用 %d分%02d秒".format(Locale.ROOT,(auto.progressFraction*100).toInt(),auto.elapsedSeconds/60,auto.elapsedSeconds%60),style=MaterialTheme.typography.bodySmall)
                    if(remaining!=null && auto.phase in setOf(io.github.ibudaoa.running.AutomaticRunPhase.RUNNING,io.github.ibudaoa.running.AutomaticRunPhase.PAUSED))
                        Text((if(auto.phase==io.github.ibudaoa.running.AutomaticRunPhase.PAUSED) "继续后预计还需 " else "预计剩余 ")+"%d分%02d秒（不含提交等待）".format(Locale.ROOT,remaining/60,remaining%60),style=MaterialTheme.typography.bodySmall)
                    Text(when(auto.phase) {
                        io.github.ibudaoa.running.AutomaticRunPhase.STARTING -> "正在登记本次自动任务"
                        io.github.ibudaoa.running.AutomaticRunPhase.RUNNING -> "自动执行中 · %.2f / %.2f km · 经过 %d/%d 点".format(Locale.ROOT,auto.meters/1000,auto.targetMeters/1000,auto.visitedPoints,auto.requiredPoints)
                        io.github.ibudaoa.running.AutomaticRunPhase.PAUSED -> "自动执行已暂停 · 保留原路线和点位，暂停计入总用时"
                        io.github.ibudaoa.running.AutomaticRunPhase.FINISHING -> "正在自动上传并提交本次记录"
                        io.github.ibudaoa.running.AutomaticRunPhase.AWAITING_VERDICT -> "记录已创建，等待核对平台判定"
                        io.github.ibudaoa.running.AutomaticRunPhase.VALID -> "平台已确认本次记录有效"
                        io.github.ibudaoa.running.AutomaticRunPhase.INVALID -> "本次记录未被判定有效"
                        io.github.ibudaoa.running.AutomaticRunPhase.INTERRUPTED -> "自动任务已中断，需核对原记录"
                        else -> "自动执行准备未完成，请导出诊断"
                    },style=MaterialTheme.typography.bodyMedium)
                    if(auto.phase==io.github.ibudaoa.running.AutomaticRunPhase.INTERRUPTED && auto.pendingWrite=="FINISH" && !auto.recordBound)
                        TextButton(onClick={confirmArchive=true},enabled=!state.busy) { Text("官方无记录，处理本地任务") }
                    state.automaticFailureReason?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                    if(auto.phase in setOf(io.github.ibudaoa.running.AutomaticRunPhase.RUNNING,io.github.ibudaoa.running.AutomaticRunPhase.PAUSED))
                        TextButton(onClick={confirmAutomaticFinish=true},enabled=!state.busy) { Text("结束并提交本次记录") }
                }
                Button(onClick = if(auto.phase==io.github.ibudaoa.running.AutomaticRunPhase.FAILED && !auto.platformStarted)onPrepareRunningPlan else if(active || planned)onAutomaticAction else if(ready)onPrepareRunningPlan else onWarmup,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),shape = RoundedCornerShape(30.dp),
                    enabled = !state.busy && auto.phase !in setOf(io.github.ibudaoa.running.AutomaticRunPhase.STARTING,io.github.ibudaoa.running.AutomaticRunPhase.FINISHING)) {
                    Text(when {
                        auto.phase==io.github.ibudaoa.running.AutomaticRunPhase.RUNNING -> "暂停"
                        auto.phase==io.github.ibudaoa.running.AutomaticRunPhase.PAUSED -> "继续"
                        auto.phase in setOf(io.github.ibudaoa.running.AutomaticRunPhase.VALID,io.github.ibudaoa.running.AutomaticRunPhase.INVALID) -> "准备下一次"
                        active -> "核对本次记录"
                        planned -> "开始自动运行"
                        ready -> "生成运行计划"
                        else -> "完成准备"
                    },style=MaterialTheme.typography.titleLarge)
                }
            }
            if(runningPage)Text("自动执行时保持应用前台亮屏，无需本人走跑。", Modifier.fillMaxWidth().padding(vertical = 14.dp),
                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun SettingsPage(state: AppState, onExit: () -> Unit, onConnect: () -> Unit, onAccountCheck: () -> Unit, onMessage: (String) -> Unit, onDisableMap: () -> Unit, onCheckUpdates: () -> Unit, onDiagnosticMode: (Boolean) -> Unit) {
    val profile = state.capture.profile?.takeIf {
        state.capturedConfig && state.capture.phase == CapturePhase.CAPTURED &&
            it.schoolName != null && it.schoolName == state.config?.schoolName
    }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = colors.primaryContainer), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Default.AccountCircle, null, Modifier.size(64.dp), tint = colors.primary)
                    Column {
                        Text(profile?.name ?: if (state.capturedConfig) "已读取官方配置" else "未连接账号", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (state.accountSession.credentialsPersisted) "登录已在本机加密保存"
                            else if (state.capturedConfig) "已读取配置，登录尚未保存" else "连接后读取学校配置", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(if (state.capturedConfig) "${state.config?.schoolName ?: "学校名称待核对"}\n${state.config?.campusName ?: "跑区名称待核对"}" else "请先在官方应用登录，再连接读取学校配置。完整账号资料将在后续版本接入。",
                    style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick=onAccountCheck,enabled=!state.busy && !state.capture.active) { Text("账号连接") }
                Text(state.accountSession.label,style=MaterialTheme.typography.bodySmall)
                if (state.capture.purpose==CapturePurpose.ACCOUNT_READ_CHECK && state.capture.accountRead!=null)
                    Text(when(state.capture.accountRead.outcome) {
                        AccountReadOutcome.PASSED -> "本次只读校验通过 · 未保留会话"
                        AccountReadOutcome.READ_ACCEPTED_UNBOUND -> "独立读取成功 · 账号归属未核实 · 未保留会话"
                        else -> "本次只读校验未通过"
                    },style=MaterialTheme.typography.bodySmall)
                HorizontalDivider(color = colors.primary.copy(alpha = 0.12f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = onConnect) {
                        Icon(Icons.Default.Link, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.capturedConfig) "重新连接" else "连接账号")
                    }
                    TextButton(onClick = onExit, enabled = state.capturedConfig && !state.capture.active && !state.busy) {
                        Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("退出连接")
                    }
                }
            }
        }
        if (state.mapConsent) TextButton(onClick = onDisableMap, enabled = !state.busy) { Text("停用地图与定位") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("调试模式")
            Text("保留少量诊断以排查问题，关闭后清除。", style=MaterialTheme.typography.bodySmall)
        }
        androidx.compose.material3.Switch(checked=state.diagnosticMode, onCheckedChange=onDiagnosticMode,
            enabled=!state.busy && !state.automaticRun.blocksExternalUi)
    }
    val repository=io.github.ibudaoa.updates.ReleaseRepository.parse(BuildConfig.REPOSITORY_URL)
        val uriHandler=LocalUriHandler.current
        val inspecting = LocalInspectionMode.current
        fun openPage(url:String) {
            if (inspecting) return
            if(state.automaticRun.blocksExternalUi)onMessage("请等待本次执行结束后再打开外部页面。")
            else runCatching { uriHandler.openUri(url) }.onFailure { onMessage("无法打开页面，请检查浏览器。") }
        }
        ToolCard("GitHub 仓库", repository?.let { "${it.owner}/${it.name}" } ?: "仓库地址待配置", Icons.Default.Code, Icons.AutoMirrored.Filled.OpenInNew) {
            if(repository!=null)openPage(repository.url) else onMessage("项目仓库尚未配置，当前没有可打开的仓库链接。")
        }
        val update=state.updateCheck
        ToolCard("检查更新", "当前版本 ${BuildConfig.VERSION_NAME} · ${update.label}", Icons.Default.Refresh, Icons.AutoMirrored.Filled.OpenInNew) {
            val page=update.releaseUrl
            if(page!=null && update.phase in setOf(io.github.ibudaoa.updates.UpdatePhase.AVAILABLE,io.github.ibudaoa.updates.UpdatePhase.UNSUPPORTED))openPage(page)
            else onCheckUpdates()
        }
    }
}

@Composable
private fun ToolCard(title: String, subtitle: String, icon: ImageVector, trailing: ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(trailing, null, Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun WarmupDialog(state: AppState, onContinue: () -> Unit, onDismiss: () -> Unit) {
    val capture = state.capture
    val roads = state.roadReport
    val needsRoadReview = roads != null && roads.reviewRevision != io.github.ibudaoa.roads.RoadBatchReport.CURRENT_REVIEW_REVISION
    val needsCapture = state.capturedConfig && state.captureRevision != DiagnosticStore.CURRENT_CAPTURE_REVISION
    val missing = state.config?.structure?.missingSections.orEmpty()
    val current = when {
        capture.phase == CapturePhase.LISTENING -> 2
        capture.phase == CapturePhase.STARTING -> 1
        state.capturedConfig && state.certificateTrusted ->
            if (missing.isEmpty() && state.config?.stageVerification != StageVerification.UNKNOWN) 3 else 2
        state.certificateTrusted -> 1
        else -> 0
    }
    val heading = when {
        !capture.active && needsRoadReview -> "更新道路检查"
        !capture.active && roads?.phase == RoadBatchPhase.RUNNING -> "正在核对候选道路"
        !capture.active && roads?.phase == RoadBatchPhase.COMPLETE -> "检查结果已就绪"
        !capture.active && roads?.phase == RoadBatchPhase.FAILED -> "道路检查未完成"
        !capture.active && roads?.phase == RoadBatchPhase.INTERRUPTED -> "道路检查已中断"
        !capture.active && needsCapture -> "补齐学校配置"
        capture.active -> if (capture.phase == CapturePhase.STARTING) "正在连接官方应用" else "同步学校配置"
        capture.phase == CapturePhase.ERROR -> "连接尚未完成"
        state.capturedConfig && state.config?.stageVerification == StageVerification.REQUIRED -> "当前核验要求暂不支持"
        state.capturedConfig -> "学校配置已采集"
        !state.certificateTrusted -> "准备本机连接证书"
        else -> "连接官方账号"
    }
    val roadFailureNote = when (roads?.links?.lastOrNull()?.failure) {
        io.github.ibudaoa.roads.RoadLinkFailure.AUTHORIZATION -> "道路服务授权未通过。"
        io.github.ibudaoa.roads.RoadLinkFailure.QUOTA -> "道路服务额度或请求频率受限。"
        io.github.ibudaoa.roads.RoadLinkFailure.NETWORK -> "道路服务网络连接未完成。"
        else -> ""
    }
    val description = when {
        !capture.active && needsRoadReview -> "检查器已更新。继续只重新核对道路，沿用已补齐的学校配置，不需要再次进入官方应用。不会自动启动运动。"
        !capture.active && roads?.phase == RoadBatchPhase.RUNNING ->
            "已核对 ${roads.links.size}/${roads.plannedLinks} 段。请保持应用前台；只查询官方候选间道路，不使用你的位置，也不创建运动。"
        !capture.active && roads?.phase in setOf(RoadBatchPhase.COMPLETE, RoadBatchPhase.FAILED) ->
            roadFailureNote + "已取得 ${roads?.links?.size}/${roads?.plannedLinks} 段检查结果。导出检查结果后交回核对，文件合并本次学校配置和道路摘要；道路接口成功不等于完整路线或运动有效。"
        !capture.active && roads?.phase == RoadBatchPhase.INTERRUPTED ->
            "已停止查询并保留完成摘要。保持应用前台后点击重试；不会自动恢复或启动运动。"
        !capture.active && needsCapture ->
            "旧导出未保留完整的星期和日期字段，需要补读一次。继续后进入官方校园跑准备页，不点击开始；采集成功返回这里，再继续核对道路。无需重新安装证书。"
        capture.active || capture.phase == CapturePhase.ERROR -> capture.message
        state.capturedConfig -> if (state.config?.stageVerification == StageVerification.UNKNOWN)
            "已保存学校配置，阶段核验要求仍待核对；当前不会开始运动，无需为此重复采集。"
            else if (state.config?.stageVerification == StageVerification.REQUIRED)
                "学校配置明确要求人脸核验，当前版本尚不支持，暂不能开始运动。"
            else if (state.config?.stageVerification == StageVerification.SKIPPED_EMPTY)
                "本次人脸配置为空，按当前策略暂时跳过。继续检查地图与前台定位；日期和道路仍待核对，暂不开放运动。"
            else if (missing.isEmpty()) "已保存脱敏配置。规则与真实地图仍需核对，当前不会开始运动。"
            else "已保存收到的脱敏配置，仍有关键配置缺失。请导出诊断配置交回核对。"
        !state.certificateTrusted -> "保存本应用生成的 CA 证书，在系统设置中选择“安装 CA 证书”，安装后返回这里。私钥只留在本机，应用会检查安装结果。"
        else -> "请确认步道乐跑已登录。继续后允许临时 VPN，在官方应用进入校园跑准备页，不要点击开始运动；读到配置后返回这里。"
    }
    val button = when {
        state.busy || capture.phase == CapturePhase.STARTING -> "正在准备…"
        capture.phase == CapturePhase.LISTENING -> "返回官方应用"
        !capture.active && needsRoadReview -> "继续核对道路"
        !capture.active && roads?.phase in setOf(RoadBatchPhase.COMPLETE, RoadBatchPhase.FAILED) -> "导出检查结果"
        !capture.active && roads?.phase == RoadBatchPhase.INTERRUPTED -> "重试检查"
        state.capturedConfig -> "继续"
        !state.certificateTrusted && !state.certificateExported && !state.preparationAcknowledged -> "开始"
        !state.certificateTrusted && state.certificateExported -> "安装证书"
        else -> "继续"
    }
    val content: @Composable () -> Unit = {
        Surface(Modifier.padding(horizontal = 16.dp, vertical = 24.dp).widthIn(max = 560.dp)
            .fillMaxWidth().fillMaxHeight(0.78f), shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, if (capture.active) "结束采集并关闭" else "关闭跑前热身")
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text("跑前热身", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text("已完成 $current / 4 项", Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(32.dp))
                    Stepper(current)
                    Spacer(Modifier.height(28.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(28.dp))
                    Text(heading, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text(description, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("临时连接仅用于读取官方应用的学校配置，两分钟内自动结束。采集期间其他官方功能可能暂不可用。运动仅支持前台亮屏，不做小窗。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = onContinue, enabled = !state.busy && capture.phase != CapturePhase.STARTING,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(28.dp)) {
                    Text(button, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
    // Layoutlib renders the same card inline; the device retains its modal window.
    if (LocalInspectionMode.current) content()
    else Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false), content = content)
}

private fun formatRuleTime(seconds: Int): String = if (seconds % 60 == 0)
    String.format(Locale.ROOT, "%02d:%02d", seconds / 3600, seconds / 60 % 60)
    else String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)

private fun formatPace(seconds: Int): String = String.format(Locale.ROOT, "%d′%02d″", seconds / 60, seconds % 60)

@Composable
private fun Stepper(current: Int) {
    val labels = listOf("授权准备", "连接账号", "同步学校", "运行准备")
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val unit = size.width / 4
            repeat(3) { index ->
                drawLine(if (index < current) colors.primary else colors.outlineVariant,
                    Offset(unit * (index + 0.5f), size.height / 2),
                    Offset(unit * (index + 1.5f), size.height / 2), strokeWidth = 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                val completed = index < current
                Column(Modifier.weight(1f).semantics {
                    contentDescription = "$label，${if (completed) "已完成" else if (index == current) "当前步骤" else "未完成"}"
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(36.dp).background(if (completed) colors.primary else colors.surface, CircleShape)
                        .border(2.dp, if (index <= current) colors.primary else colors.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center) {
                        if (completed) Icon(Icons.Default.Check, null, Modifier.size(22.dp), tint = colors.onPrimary)
                        else if (index == current) Box(Modifier.size(10.dp).background(colors.primary, CircleShape))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(label, textAlign = TextAlign.Center, fontSize = 11.sp)
                }
            }
        }
    }
}

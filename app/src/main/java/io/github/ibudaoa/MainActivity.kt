package io.github.ibudaoa

import android.os.Bundle
import android.content.Intent
import android.net.VpnService
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.ibudaoa.capture.CaptureVpnService
import io.github.ibudaoa.capture.ReadOnlyPolicy
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import io.github.ibudaoa.ui.IbudaoaApp
import io.github.ibudaoa.ui.IbudaoaTheme

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    private val importConfig = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::importConfig)
    }
    private val exportConfig = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(model::exportConfig)
    }
    private val exportConnectionReport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(model::exportConnectionReport)
    }
    private var locationRevision by mutableIntStateOf(0)
    private var locationRequestInFlight = false
    private var checkLocationOnNextStart = false
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationRequestInFlight = false
        refreshLocationPermission()
    }
    private val locationSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        locationRequestInFlight = false
        refreshLocationPermission()
    }

    private fun refreshLocationPermission() {
        model.refreshLocationPermission()
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences("location-permission", MODE_PRIVATE).edit().clear().apply()
        }
        locationRevision++
    }

    private fun canRequestLocation(): Boolean {
        val prefs = getSharedPreferences("location-permission", MODE_PRIVATE)
        return io.github.ibudaoa.domain.LocationPermissionPolicy.canRequest(
            prefs.getBoolean("asked", false),
            shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_FINE_LOCATION),
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            prefs.getBoolean("precision-upgrade-asked", false))
    }

    private fun requestLocationPermission() {
        if (locationRequestInFlight || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(fine) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            refreshLocationPermission()
            return
        }
        val prefs = getSharedPreferences("location-permission", MODE_PRIVATE)
        locationRequestInFlight = true
        try {
            if (!canRequestLocation()) {
                locationSettings.launch(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")))
            } else {
                prefs.edit().putBoolean("asked", true).apply {
                    if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                        putBoolean("precision-upgrade-asked", true)
                }.apply()
                locationPermission.launch(arrayOf(fine, android.Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        } catch (_: Exception) {
            locationRequestInFlight = false
            model.showMessage("无法打开定位授权，请在应用设置中开启定位。")
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestVpnConsent()
    }
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startCollector()
        else model.showMessage("未授予 VPN 连接权限，采集未启动。")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationRequestInFlight = savedInstanceState?.getBoolean("location-request-in-flight") ?: false
        refreshLocationPermission()
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.captureActions.collect { action ->
                    if (action != CaptureAction.STOP_CAPTURE && !model.captureRequested) return@collect
                    when (action) {
                        CaptureAction.REQUEST_VPN -> requestVpn()
                        CaptureAction.OPEN_OFFICIAL -> openOfficial()
                        CaptureAction.STOP_CAPTURE -> CaptureVpnService.stopOwned(this@MainActivity)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    model.refreshRouteDraft()
                    delay(30_000)
                }
            }
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            DisposableEffect(state.session.keepScreenOn) {
                if (state.session.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            CompositionLocalProvider(io.github.ibudaoa.maps.LocalLocationPermission provides
                io.github.ibudaoa.maps.LocationPermissionAccess(locationRevision,
                    canRequestLocation(),
                    ::requestLocationPermission)) {
                IbudaoaTheme {
                    io.github.ibudaoa.maps.ForegroundLocationProvider(
                        enabled=BuildConfig.MAP_KEY_CONFIGURED && state.mapConsent && state.config!=null,
                        permissionRevision=locationRevision,onFix=model::updateDisplayFix,onDiagnostics=model::updateLocationDiagnostics,
                    ) {
                        IbudaoaApp(
                            state = state,
                            events = model.events,
                            onDiagnosticMode = model::setDiagnosticMode,
                            onAcknowledgePreparation = { model.continuePreparation(lifecycle) },
                            onImport = { importConfig.launch(arrayOf("application/json", "text/plain")) },
                            onExport = { if(state.automaticRun.blocksExternalUi)model.showMessage("请等待本次执行与记录核对结束后再导出，打开文件选择器会离开前台。") else exportConfig.launch("ibudaoa-diagnostic.json") },
                            onExportConnectionReport = { if(state.automaticRun.blocksExternalUi)model.showMessage("请等待本次执行与记录核对结束后再导出。") else exportConnectionReport.launch("ibudaoa-connection-report.json") },
                            onClear = model::clearDiagnostic,
                            onMessage = model::showMessage,
                            onMapConsent = model::setMapConsent,
                            onLocationStartupHandled = model::acknowledgeLocationStartup,
                            onReconnect = model::reconnectAccount,
                            onCheckRoadGaps = { model.checkRoadGaps(lifecycle) },
                            onSetManualGap = model::setManualGap,
                            onSetReviewedDirection = model::setReviewedDirection,
                            onConfirmAllDirections = model::confirmAllReviewedDirections,
                            onConfirmAllRoadConnections = model::confirmAllRoadConnectionsAndDirections,
                            onPrepareLocalPreview = model::prepareLocalPreview,
                            onPrepareRunningPlan = { model.prepareRunningPlan(lifecycle) },
                            onAutomaticAction = { model.automaticAction(lifecycle) },
                            onFinishAutomatic = model::finishAutomatic,
                            onArchiveAutomatic = model::archiveManuallyReviewedAutomatic,
                            onCheckUpdates = model::checkUpdates,
                            onCancelCapture = {
                                model.cancelCapture()
                                CaptureVpnService.stopOwned(this)
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("location-request-in-flight", locationRequestInFlight)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (checkLocationOnNextStart) {
            checkLocationOnNextStart = false
            model.onLocationStartup()
        }
        model.onForegroundReturned()
    }

    override fun onResume() {
        super.onResume()
        refreshLocationPermission()
        model.onAutomaticForegroundReturned()
        val returnedGeneration=intent.getLongExtra("capture_return_generation",-1L)
        if (returnedGeneration == io.github.ibudaoa.capture.CaptureRuntime.state.value.generation &&
            io.github.ibudaoa.capture.CaptureRuntime.state.value.phase == io.github.ibudaoa.capture.CapturePhase.CAPTURED) {
            android.util.Log.i("CaptureStartup","returned_foreground")
        }
        intent.removeExtra("capture_return_generation")
        getSystemService(android.app.NotificationManager::class.java).cancel(CaptureVpnService.RETURN_NOTIFICATION_ID)
        model.refreshCertificateStatus()
        if (io.github.ibudaoa.capture.CaptureReturnPolicy.shouldRelease(io.github.ibudaoa.capture.CaptureRuntime.state.value)) {
            CaptureVpnService.finishOnReturn(this)
        }
    }

    private fun requestVpn() {
        if (!model.captureRequested) return
        val prefs = getSharedPreferences("capture-notification", MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("asked", false)) {
            prefs.edit().putBoolean("asked",true).apply()
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestVpnConsent()
    }

    private fun requestVpnConsent() {
        if (!model.captureRequested) return
        try {
            val intent = VpnService.prepare(this)
            if (intent == null) startCollector() else vpnPermission.launch(intent)
        } catch (_: Exception) { model.showMessage("无法请求 VPN 授权，请检查系统设置。") }
    }

    private fun startCollector() {
        if (!model.captureRequested) return
        try {
            startForegroundService(Intent(this, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_START)
                .putExtra("account_read_check",model.requestedCapturePurpose==io.github.ibudaoa.capture.CapturePurpose.ACCOUNT_READ_CHECK)
                .putExtra("account_session",model.requestedCapturePurpose==io.github.ibudaoa.capture.CapturePurpose.ACCOUNT_SESSION))
        } catch (_: Exception) { model.showMessage("系统未允许启动采集，请保持应用前台后重试。") }
    }

    private fun openOfficial() {
        if (!model.captureRequested || !model.state.value.capture.active) return
        val launch = packageManager.getLaunchIntentForPackage(ReadOnlyPolicy.OFFICIAL_PACKAGE)
        if (launch == null) {
            CaptureVpnService.stopOwned(this)
            model.showMessage("未找到步道乐跑，请先安装官方应用并完成登录。")
            return
        }
        try { startActivity(launch); model.markOfficialOpened() }
        catch (_: Exception) {
            CaptureVpnService.stopOwned(this)
            model.showMessage("无法打开官方应用，临时采集已停止。")
        }
    }

    override fun onPause() {
        model.interruptAutomatic()
        model.cancelRoadCheck()
        super.onPause()
    }

    override fun onStop() {
        if (!isChangingConfigurations && !locationRequestInFlight) checkLocationOnNextStart = true
        if (!isChangingConfigurations) model.onForegroundLost()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
}

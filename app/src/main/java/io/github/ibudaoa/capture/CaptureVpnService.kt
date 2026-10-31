package io.github.ibudaoa.capture

import android.app.Notification
import android.util.Log
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import io.github.ibudaoa.MainActivity
import io.github.ibudaoa.R
import io.github.ibudaoa.data.DiagnosticStore
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.system.ErrnoException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import io.github.ibudaoa.diagnostics.DiagnosticConfig

/** Temporary, explicitly user-started config collection. Never a movement/background runner. */
class CaptureVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val resourceLock = Any()
    private val captured = AtomicBoolean(false)
    private val returning = AtomicBoolean(false)
    private val schoolProbe = AtomicBoolean(false)
    @Volatile private var pendingSchool: DiagnosticConfig? = null
    private var pendingSchoolProof: ByteArray? = null
    private var independentSchool = false
    @Volatile private var schoolTransport: AccountReadConnection? = null
    private val startedAt = android.os.SystemClock.elapsedRealtime()
    private var generation = 0L
    private var sessionTicket=0L
    private var purpose = CapturePurpose.CONFIG
    private var tun: ParcelFileDescriptor? = null
    private var proxy: ReadOnlyProxy? = null
    private var drainThread:Thread?=null
    @Volatile private var destroyed = false
    private val timeout = Runnable {
        fail(CaptureProblem.TIMEOUT)
    }

    override fun onCreate() {
        super.onCreate()
        instance=this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (intent.getLongExtra("generation", generation) == generation) stopCapture()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        if (generation == 0L && !destroyed) purpose=when {
            intent.getBooleanExtra("account_session",false) -> CapturePurpose.ACCOUNT_SESSION
            intent.getBooleanExtra("account_read_check",false) -> CapturePurpose.ACCOUNT_READ_CHECK
            else -> CapturePurpose.CONFIG
        }
        Log.i("CaptureStartup", "start_requested")
        try {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            } else startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            Log.w("CaptureStartup", "foreground_failed")
            if (generation == 0L) generation = CaptureRuntime.begin(purpose)
            fail(CaptureProblem.VPN); return START_NOT_STICKY
        }
        Log.i("CaptureStartup", "foreground_ready")
        // A late start may reach an instance already being torn down. Fulfil the foreground
        // contract before stopping, and never establish a replacement connection from it.
        if (destroyed) { stopSelf(); return START_NOT_STICKY }
        if (generation != 0L) return START_NOT_STICKY
        running=true
        generation = CaptureRuntime.begin(purpose)
        handler.postDelayed(timeout, 120_000)
        scope.launch { startCollector() }
        return START_NOT_STICKY
    }

    private fun startCollector() {
        try {
            AccountSessionRuntime.initialize(this)
            if (destroyed) return
            if (purpose == CapturePurpose.ACCOUNT_SESSION) {
                val ticket = AccountSessionRuntime.session.begin()
                sessionTicket = ticket
                if (destroyed) { AccountSessionRuntime.session.captureEnded(ticket); return }
            }
            Log.i("CaptureStartup", "initializing")
            val certificates = CaptureCertificates(this)
            if (!certificates.isInstalled()) { fail(CaptureProblem.CERTIFICATE); return }
            val context = certificates.serverContext()
            val local = ReadOnlyProxy(context, onBeforeRun = onResponse@ { bytes ->
                if (purpose == CapturePurpose.ACCOUNT_READ_CHECK || destroyed) return@onResponse
                pendingSchool = try { BeforeRunDecoder.decode(bytes) }
                catch (e: BeforeRunAccountException) { fail(e.problem); return@onResponse }
                catch (_: Exception) { report(CaptureProblem.INVALID_RESPONSE); return@onResponse }
                Log.i("CaptureStartup", "school_received")
                completeIfReady()
            }, onAccountProfile = { bytes ->
                val profile = runCatching { AccountProfileDecoder.decode(bytes) }.getOrNull()
                if (profile != null) CaptureRuntime.update(generation) { it.copy(profile = profile) }
            }, accountCheckOnly=purpose!=CapturePurpose.CONFIG, onAccountRead={ result ->
                if (!destroyed) {
                    if(purpose==CapturePurpose.ACCOUNT_SESSION) AccountSessionRuntime.session.acquisition(sessionTicket,result)
                    CaptureRuntime.update(generation) { it.copy(accountRead=result) }
                    Log.i("CaptureStartup", "account_${result.outcome.name}")
                    if (result.readAccepted) {
                        if (purpose == CapturePurpose.ACCOUNT_READ_CHECK) completeSuccessfully()
                        else completeIfReady()
                    } else fail(CaptureProblem.ACCOUNT_CHECK)
                }
            }, onAcceptedAccount=if(purpose==CapturePurpose.ACCOUNT_SESSION) { accepted ->
                if(!destroyed) AccountSessionRuntime.session.accept(sessionTicket,accepted)
                Unit
            } else null, accountMaintenance=purpose==CapturePurpose.ACCOUNT_SESSION,
                onAccountProtocol={ request,response ->
                    val context=try { AccountProtocol.capture(request,response) }
                        catch(e:ProtocolCaptureFailure) { AccountSessionRuntime.session.protocolProblem(sessionTicket,e.problem);null }
                    if(context!=null) try {
                        if(!destroyed) {
                            AccountSessionRuntime.session.offerProtocol(sessionTicket,context)
                            if (request.target == ReadOnlyPolicy.BEFORE_RUN) {
                                pendingSchoolProof?.fill(0)
                                pendingSchoolProof=context.clone()
                                independentSchool=false
                            } else completeIfReady()
                        }
                    } finally { context.fill(0) }
                    else if (request.target == ReadOnlyPolicy.BEFORE_RUN) {
                        pendingSchoolProof?.fill(0); pendingSchoolProof=null
                    }
                }, onBlocked = { kind ->
                CaptureRuntime.update(generation) { it.copy(blockedRequests = it.blockedRequests + 1,
                    blockedKinds = it.blockedKinds + kind) }
            }, onProblem = { problem -> report(problem) })
            synchronized(resourceLock) {
                if (destroyed) { local.close(); return }
                proxy = local
                val configure = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                tun = Builder().setSession(if (purpose!=CapturePurpose.CONFIG) "ibudaoa 账号只读校验" else "ibudaoa 只读配置采集")
                    .addAddress("10.111.0.2", 32).addRoute("0.0.0.0", 0)
                    .addAllowedApplication(ReadOnlyPolicy.OFFICIAL_PACKAGE)
                    .setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", local.port))
                    .setConfigureIntent(configure).setBlocking(false).establish()
                    ?: error("VPN not established")
                val descriptor = tun ?: error("VPN not established")
                drainThread=thread(name = "diagnostic-vpn-drain", isDaemon = true) {
                    // Bounded poll + nonblocking read: a quiet TUN must never hold shutdown indefinitely.
                    // Non-proxy official traffic remains blocked while the temporary collector is active.
                    val buffer=ByteArray(32768)
                    val poll=StructPollfd().apply { fd=descriptor.fileDescriptor;events=OsConstants.POLLIN.toShort() }
                    try {
                        while(!destroyed && descriptor.fileDescriptor.valid()) {
                            if(Os.poll(arrayOf(poll),250)<=0)continue
                            if(poll.revents.toInt() and (OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL)!=0)break
                            if(poll.revents.toInt() and OsConstants.POLLIN!=0) {
                                try { if(Os.read(descriptor.fileDescriptor,buffer,0,buffer.size)<=0)break }
                                catch(e:ErrnoException) { if(e.errno!=OsConstants.EAGAIN && e.errno!=OsConstants.EINTR)throw e }
                            }
                        }
                    } catch(_:Exception) { /* Closed by the owning service; never establish a replacement. */ }
                    finally { buffer.fill(0) }
                }
            }
            Log.i("CaptureStartup", "collector_ready")
            CaptureRuntime.update(generation) { it.copy(phase = CapturePhase.LISTENING,
                message = if (purpose!=CapturePurpose.CONFIG)
                    if(purpose==CapturePurpose.ACCOUNT_SESSION) "请在官方应用先打开“我的”，再进入校园跑准备页后返回；不要点击开始。"
                    else "请在官方应用打开“我的”页，触发账号资料读取后返回。"
                    else "请在官方应用进入校园跑准备页，不要点击开始；读到配置后返回这里。") }
            local.serve()
        } catch (_: Exception) { if (!destroyed) fail(CaptureProblem.VPN) }
    }

    private fun report(problem: CaptureProblem) {
        Log.w("CaptureStartup", "problem_${problem.name}")
        CaptureRuntime.update(generation) { it.copy(problem=problem,message=problem.userMessage) }
    }

    /** Called serially for copied, fully received responses. No client socket is needed here. */
    private fun completeIfReady() {
        if (destroyed || captured.get()) return
        val session = AccountSessionRuntime.session
        if (purpose == CapturePurpose.ACCOUNT_SESSION && !session.captureReady(sessionTicket)) return
        if (pendingSchool == null && purpose == CapturePurpose.ACCOUNT_SESSION && schoolProbe.compareAndSet(false,true)) {
            try {
                val transport = AccountReadTransport(maintenance=true)
                schoolTransport = transport
                pendingSchool = session.readCaptureSchool(sessionTicket,transport,System.currentTimeMillis()/1000,
                    100_000+java.security.SecureRandom().nextInt(900_000))
                independentSchool = pendingSchool != null
            } catch (e: BeforeRunAccountException) { fail(e.problem); return }
            catch (_: Exception) { report(CaptureProblem.NETWORK) }
            finally { schoolTransport = null }
        }
        val school = pendingSchool ?: return
        if (purpose == CapturePurpose.ACCOUNT_SESSION) {
            if (!session.captureSchoolMatches(sessionTicket,school)) { report(CaptureProblem.ACCOUNT_CHANGED); return }
            if (!independentSchool) {
                val proof = pendingSchoolProof
                if (proof == null) { report(CaptureProblem.REQUEST_FORMAT); return }
                if (!session.captureContextMatches(sessionTicket,proof)) { report(CaptureProblem.ACCOUNT_CHANGED); return }
            }
        }
        if (!captured.compareAndSet(false,true) || destroyed) return
        try {
            runBlocking(scope.coroutineContext) {
                if (!destroyed) DiagnosticStore(this@CaptureVpnService).save(school,captured=true)
            }
            if (!destroyed) {
                CaptureRuntime.update(generation) { it.copy(config=school) }
                completeSuccessfully()
            }
        } catch (_: CancellationException) { /* Explicit cancellation fences pending saves. */ }
        catch (_: Exception) { fail(CaptureProblem.STORAGE) }
    }

    private fun completeSuccessfully() {
        if (destroyed) return
        CaptureRuntime.update(generation) { it.copy(phase=CapturePhase.CAPTURED,problem=CaptureProblem.NONE,
            message="账号和学校资料已保存，正在返回。") }
        handler.post {
            if (!destroyed && CaptureRuntime.state.value.generation == generation &&
                CaptureRuntime.state.value.phase == CapturePhase.CAPTURED) {
                Log.i("CaptureStartup", "saved_return_${android.os.SystemClock.elapsedRealtime()-startedAt}ms")
                val manager = getSystemService(NotificationManager::class.java)
                val open = PendingIntent.getActivity(this, 3, returnIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                // Separate from the ongoing FGS notification, so it survives service shutdown.
                runCatching { manager.notify(RETURN_NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher).setContentTitle("连接已完成")
                    .setContentText("点击返回本应用继续准备").setAutoCancel(true).setContentIntent(open).build()) }
                stopCapture()
                runCatching { startActivity(returnIntent()) }.onFailure { Log.i("CaptureStartup", "return_requires_user") }
            }
        }
    }

    private fun returnIntent() = Intent(this, MainActivity::class.java)
        .putExtra("capture_return_generation",generation)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Manual return releases the VPN immediately and drains only already received responses. */
    private fun finishOnReturn() {
        if (destroyed || !returning.compareAndSet(false,true)) return
        CaptureRuntime.update(generation) { it.copy(phase=CapturePhase.FINALIZING,message="正在保存已读取的连接资料……") }
        detachTun()
        val local = synchronized(resourceLock) { proxy }
        thread(name="capture-response-drain",isDaemon=true) {
            val drained = runCatching { local?.finishReceived() != false }.getOrDefault(false)
            handler.post {
                if (!destroyed) {
                    Log.i("CaptureStartup", if(drained) "returned_drained" else "returned_drain_timeout")
                    fail(if(drained) CaptureProblem.INCOMPLETE else CaptureProblem.TIMEOUT)
                }
            }
        }
    }

    private fun detachTun() {
        val descriptor = synchronized(resourceLock) { val fd=tun; tun=null;fd }
        runCatching { descriptor?.close() }
        running=false
    }

    private fun fail(problem: CaptureProblem) {
        if (destroyed) return
        Log.w("CaptureStartup", "failed_${problem.name}")
        CaptureRuntime.update(generation) { it.copy(phase = CapturePhase.ERROR, problem = problem, message = problem.userMessage) }
        handler.post { stopCapture() }
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "临时只读采集", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 2,
            Intent(this, CaptureVpnService::class.java).setAction(ACTION_STOP).putExtra("generation", generation),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (purpose!=CapturePurpose.CONFIG) "正在只读校验账号" else "正在只读采集学校配置").setContentText("仅限官方应用，两分钟内自动停止，不启动运动。")
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "结束采集", stop).build()).build()
    }

    override fun onRevoke() { fail(CaptureProblem.REVOKED); super.onRevoke() }
    override fun onTimeout(startId: Int) { fail(CaptureProblem.TIMEOUT) }
    override fun onTimeout(startId: Int, fgsType: Int) { fail(CaptureProblem.TIMEOUT) }
    override fun onTaskRemoved(rootIntent: Intent?) { stopCapture() }

    /** Close the owned TUN before account callbacks or proxy cleanup can block. */
    private fun releaseCapture() {
        val resources=synchronized(resourceLock) {
            if(destroyed)return
            destroyed=true
            Log.i("CaptureStartup", "released")
            val owned=Triple(tun,proxy,drainThread)
            tun=null;proxy=null;drainThread=null
            owned
        }
        runCatching { resources.first?.close() }
        running=false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        // Socket disconnects can block; the system VPN route has already been removed.
        val transport=schoolTransport
        if(sessionTicket!=0L)AccountSessionRuntime.session.invalidateCapture(sessionTicket)
        thread(name="capture-proxy-cleanup",isDaemon=true) {
            runCatching { transport?.close() }
            resources.second?.let { runCatching { it.close() } }
            if(sessionTicket!=0L)AccountSessionRuntime.session.captureEnded(sessionTicket)
            pendingSchoolProof?.fill(0);pendingSchoolProof=null
        }
        pendingSchool=null
        CaptureRuntime.update(generation) { it.copy(phase=CapturePhase.CANCELLED,message="临时采集已结束。",
            problem=if(it.problem==CaptureProblem.NONE)CaptureProblem.CANCELLED else it.problem) }
    }
    private fun stopCapture() { releaseCapture();stopSelf() }
    override fun onDestroy() {
        releaseCapture()
        if(instance===this)instance=null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance:CaptureVpnService?=null
        /** Only this app's temporary service; never toggles or rejects another VPN. */
        fun finishOnReturn(context:android.content.Context) {
            val service=instance
            if(service!=null) service.finishOnReturn() else context.stopService(Intent(context,CaptureVpnService::class.java))
        }
        fun stopOwned(context:android.content.Context) {
            instance?.releaseCapture()
            context.stopService(Intent(context,CaptureVpnService::class.java))
        }
        @Volatile var running=false
            private set
        const val ACTION_START = "io.github.ibudaoa.capture.START"
        const val ACTION_STOP = "io.github.ibudaoa.capture.STOP"
        const val RETURN_NOTIFICATION_ID = 41
        private const val CHANNEL = "readonly_capture"
        private const val NOTIFICATION_ID = 40
    }
}

package io.github.ibudaoa.running

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.ibudaoa.MainActivity
import kotlinx.coroutines.*

class RunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ending=false
    private var lastUpdate=0L
    private var lastPhase:AutomaticRunPhase?=null
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        channels(this)
        startForeground(ID, notification(AutomaticRunSummary(AutomaticRunPhase.STARTING)))
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ibudaoa:run").apply { acquire(3 * 60 * 60 * 1000L) }
        scope.launch {
            RunRuntime.model(application).state.collect { state ->
                if(ending)return@collect
                val s = state.automaticRun
                if(s.phase == AutomaticRunPhase.PAUSED || s.phase == AutomaticRunPhase.IDLE) {
                    ending=true;stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                } else if(s.phase in setOf(AutomaticRunPhase.VALID, AutomaticRunPhase.INVALID, AutomaticRunPhase.FAILED, AutomaticRunPhase.INTERRUPTED) || s.manualConfirmationAvailable) {
                    ending=true
                    val text = when {
                        s.manualConfirmationAvailable -> "运动结果待确认" to "无法确定记录是否有效，请去步道乐跑确认"
                        s.phase == AutomaticRunPhase.VALID -> "运动已完成" to "记录有效"
                        s.phase == AutomaticRunPhase.INVALID -> "运动已完成" to "记录无效，请点击查看"
                        else -> "运动已暂停" to "请点击返回查看"
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    getSystemService(NotificationManager::class.java).notify(RESULT_ID,
                        NotificationCompat.Builder(this@RunService, RESULT_CHANNEL).setSmallIcon(android.R.drawable.ic_menu_compass)
                            .setContentTitle(text.first).setContentText(text.second).setContentIntent(open()).setAutoCancel(true).build())
                    stopSelf()
                } else if(lastPhase!=s.phase || SystemClock.elapsedRealtime()-lastUpdate>=1000) {
                    lastPhase=s.phase;lastUpdate=SystemClock.elapsedRealtime()
                    getSystemService(NotificationManager::class.java).notify(ID, notification(s))
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action == PAUSE) { RunRuntime.model(application).pauseFromOutside(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) { RunRuntime.model(application).pauseFromOutside(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() {
        val model=RunRuntime.model(application)
        if(model.state.value.automaticRun.phase==AutomaticRunPhase.RUNNING)model.pauseFromOutside()
        scope.cancel(); wake?.let { if(it.isHeld)it.release() }; wake=null; super.onDestroy() }
    private fun open() = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("run_notification", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification(s: AutomaticRunSummary): Notification {
        val text = when(s.phase) {
            AutomaticRunPhase.STARTING -> "剩余时间：计算中"
            AutomaticRunPhase.FINISHING -> "正在提交"
            AutomaticRunPhase.AWAITING_VERDICT -> "核验中……"
            else -> "剩余时间：%02d:%02d".format((s.remainingSeconds ?: 0)/60,(s.remainingSeconds ?: 0)%60)
        }
        val delete = PendingIntent.getBroadcast(this, 11, Intent(this, RunNotificationDeleted::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentText(text).setProgress(1000,(s.progressFraction*1000).toInt(),s.phase != AutomaticRunPhase.RUNNING)
            .setContentIntent(open()).setDeleteIntent(delete).setOnlyAlertOnce(true).setSilent(true).setOngoing(false).build()
    }
    companion object {
        const val PAUSE="io.github.ibudaoa.PAUSE_RUN"
        private const val ID=410; private const val RESULT_ID=411
        private const val CHANNEL="run-progress"; private const val RESULT_CHANNEL="run-result"
        fun channels(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
                NotificationChannel(CHANNEL,"运动进度",NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(RESULT_CHANNEL,"运动结果",NotificationManager.IMPORTANCE_DEFAULT)))
        }
        fun available(context: Context, guide: Boolean = true): Boolean {
            channels(context)
            val enabled=NotificationManagerCompat.from(context).areNotificationsEnabled() && context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
            if(!enabled && guide)runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return enabled
        }
        fun start(context: Context) { context.startForegroundService(Intent(context, RunService::class.java)) }
    }
}
class RunNotificationDeleted : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) { RunRuntime.model(context.applicationContext as Application).pauseFromOutside(); context.stopService(Intent(context, RunService::class.java)) }
}

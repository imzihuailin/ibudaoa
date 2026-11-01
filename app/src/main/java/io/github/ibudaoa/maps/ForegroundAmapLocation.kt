package io.github.ibudaoa.maps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import io.github.ibudaoa.domain.CampusCoordinate

/** One RESUMED Activity owns this session; map views never create or stop location clients. */
internal class ForegroundAmapLocation(private val context:Context) {
    private val gate=ForegroundFixGate()
    private val recovery=LocationRecovery()
    private val handler=Handler(Looper.getMainLooper())
    private var client:AMapLocationClient?=null
    private var publish:((DisplayFix?,String)->Unit)?=null
    private var diagnostics:((LocationDiagnostics)->Unit)?=null
    private var health:LocationHealthTracker?=null
    private var lastPublished:DisplayFix?=null
    private var allowed=false

    private fun environment():Boolean {
        val fine=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val enabled=runCatching { context.getSystemService(LocationManager::class.java)?.isLocationEnabled }.getOrNull()
        val permitted=fine && enabled!=false
        if(permitted!=allowed || health==null || !permitted && (health?.value?.finePermission!=fine || health?.value?.systemLocationEnabled!=enabled)) {
            closeClient();recovery.stop();lastPublished=null
            allowed=permitted;health=LocationHealthTracker(fine,enabled)
            if(permitted) { recovery.begin(SystemClock.elapsedRealtime());openClient() }
        }
        return permitted
    }
    private fun emit() {
        val snapshot=health?.value ?: return
        val fix=recovery.current(SystemClock.elapsedRealtime())
        if(lastPublished!=null && fix==null)health?.expired()
        lastPublished=fix
        val current=health?.value ?: snapshot
        diagnostics?.invoke(current)
        val label=if(recovery.recoveryAttempts>0 && current.status in setOf(LocationCheckStatus.STARTING,
                LocationCheckStatus.EXPIRED,LocationCheckStatus.NO_CALLBACK))"正在自动更新位置" else current.label
        publish?.invoke(fix,label)
    }
    private val watchdog=object:Runnable {
        override fun run() {
            if(publish==null)return
            if(environment() && recovery.poll(SystemClock.elapsedRealtime())) {
                health?.noCallback();health?.restarted(retainFailure=true);openClient()
            }
            emit()
            if(publish!=null)handler.postDelayed(this,1_000)
        }
    }
    fun start(onFix:(DisplayFix?,String)->Unit,onDiagnostics:(LocationDiagnostics)->Unit={}) {
        stop();publish=onFix;diagnostics=onDiagnostics
        environment();emit();handler.postDelayed(watchdog,1_000)
    }
    fun retry() {
        if(publish==null)return
        if(environment()) {
            recovery.recoverNow(SystemClock.elapsedRealtime())
            health?.restarted(retainFailure=true);openClient()
        }
        emit()
    }
    private fun openClient() {
        closeClient()
        val currentAttempt=recovery.generation
        val gateGeneration=gate.begin()
        try {
            AMapLocationClient.updatePrivacyShow(context,true,true)
            AMapLocationClient.updatePrivacyAgree(context,true)
            AMapLocationClientOption.setLocationProtocol(AMapLocationClientOption.AMapLocationProtocol.HTTPS)
            val locator=AMapLocationClient(context.applicationContext)
            client=locator
            locator.setLocationOption(AMapLocationClientOption().apply {
                locationMode=AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isNeedAddress=false;isLocationCacheEnable=false;isMockEnable=false;isOffset=true
                interval=2_000;httpTimeOut=10_000
            })
            locator.setLocationListener { location ->
                handler.post {
                    if(!recovery.owns(currentAttempt) || publish==null)return@post
                    if(!environment()) { emit();return@post }
                    val now=SystemClock.elapsedRealtime()
                    if(location==null || location.errorCode!=0) {
                        health?.sdkError(location?.errorCode);recovery.failed(now)
                    } else {
                        val frame=when(location.coordType) {
                            AMapLocation.COORD_TYPE_GCJ02 -> LocationCoordinateFrame.GCJ02
                            AMapLocation.COORD_TYPE_WGS84 -> LocationCoordinateFrame.WGS84
                            null -> LocationCoordinateFrame.UNKNOWN
                            else -> LocationCoordinateFrame.OTHER
                        }
                        val fix=gate.accept(gateGeneration,CampusCoordinate(location.latitude,location.longitude),location.accuracy,
                            frame==LocationCoordinateFrame.GCJ02,location.isMock,location.time,System.currentTimeMillis(),now,location.speed)
                        health?.sample(frame,location.accuracy,gate.lastRejection)
                        if(fix!=null)recovery.accepted(fix,now) else recovery.failed(now)
                    }
                    emit()
                }
            }
            locator.startLocation()
        } catch(_:Exception) { initializationFailed() }
        catch(_:LinkageError) { initializationFailed() }
    }
    private fun initializationFailed() {
        closeClient();health?.initializationFailed();recovery.failed(SystemClock.elapsedRealtime())
    }
    private fun closeClient() {
        recovery.invalidateAttempt();gate.stop()
        val previous=client;client=null
        previous?.let { runCatching { it.stopLocation() };runCatching { it.onDestroy() } }
    }
    fun stop() {
        handler.removeCallbacks(watchdog)
        closeClient();recovery.stop();allowed=false;health=null;lastPublished=null
        publish=null;diagnostics=null
    }
}

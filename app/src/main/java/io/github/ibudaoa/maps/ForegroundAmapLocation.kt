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

/** Owned by a RESUMED map view. Never enables SDK background mode or a location foreground service. */
internal class ForegroundAmapLocation(private val context: Context) {
    private val gate=ForegroundFixGate()
    private val handler=Handler(Looper.getMainLooper())
    private var client: AMapLocationClient?=null
    private var attempt=0L
    private var publish: ((DisplayFix?,String)->Unit)?=null
    private var diagnostics: ((LocationDiagnostics)->Unit)?=null
    private var health: LocationHealthTracker?=null
    private fun emit(fix: DisplayFix?) {
        val snapshot=health?.value ?: return
        diagnostics?.invoke(snapshot)
        publish?.invoke(fix,snapshot.label)
    }
    private val noCallback=Runnable {
        health?.noCallback()
        if (health?.value?.status == LocationCheckStatus.NO_CALLBACK) emit(null)
    }
    private val expiry=object:Runnable {
        override fun run() {
            if (publish == null) return
            // Do not overwrite SDK errors or sample rejection reasons with a generic waiting message.
            if (gate.expireIfNeeded(SystemClock.elapsedRealtime())) { health?.expired();emit(null) }
            handler.postDelayed(this,1_000)
        }
    }

    fun start(onFix: (DisplayFix?,String)->Unit, onDiagnostics: (LocationDiagnostics)->Unit = {}) {
        stop()
        publish=onFix; diagnostics=onDiagnostics
        val fine=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val enabled=runCatching { context.getSystemService(LocationManager::class.java)?.isLocationEnabled }.getOrNull()
        health=LocationHealthTracker(fine,enabled)
        emit(null)
        if (!fine || enabled == false) return
        attempt=gate.begin()
        val currentAttempt=attempt
        try {
            AMapLocationClient.updatePrivacyShow(context,true,true)
            AMapLocationClient.updatePrivacyAgree(context,true)
            // This bundled SDK defaults to HTTP; the app deliberately forbids cleartext networking.
            AMapLocationClientOption.setLocationProtocol(AMapLocationClientOption.AMapLocationProtocol.HTTPS)
            val locator=AMapLocationClient(context.applicationContext)
            client=locator
            locator.setLocationOption(AMapLocationClientOption().apply {
                locationMode=AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isNeedAddress=false
                isLocationCacheEnable=false
                isMockEnable=false
                isOffset=true
                interval=2_000
                httpTimeOut=10_000
            })
            locator.setLocationListener { location ->
                handler.post {
                    if (currentAttempt!=attempt || publish==null) return@post
                    if (location==null || location.errorCode!=0) {
                        gate.invalidate(); health?.sdkError(location?.errorCode);emit(null)
                    } else {
                        val frame=when (location.coordType) {
                            AMapLocation.COORD_TYPE_GCJ02 -> LocationCoordinateFrame.GCJ02
                            AMapLocation.COORD_TYPE_WGS84 -> LocationCoordinateFrame.WGS84
                            null -> LocationCoordinateFrame.UNKNOWN
                            else -> LocationCoordinateFrame.OTHER
                        }
                        val fix=gate.accept(currentAttempt,CampusCoordinate(location.latitude,location.longitude),
                            location.accuracy,frame==LocationCoordinateFrame.GCJ02,location.isMock,
                            location.time,System.currentTimeMillis(),SystemClock.elapsedRealtime(),location.speed)
                        health?.sample(frame,location.accuracy,gate.lastRejection)
                        emit(fix)
                    }
                }
            }
            locator.startLocation()
            handler.post(expiry)
            handler.postDelayed(noCallback,10_000)
        } catch (_: Exception) {
            val failed=health
            val output=publish
            val diagnosticOutput=diagnostics
            failed?.initializationFailed()
            stop()
            failed?.value?.let { diagnosticOutput?.invoke(it);output?.invoke(null,it.label) }
        }
    }

    fun stop() {
        attempt++
        gate.stop()
        publish=null;diagnostics=null;health=null
        handler.removeCallbacks(expiry);handler.removeCallbacks(noCallback)
        client?.let { locator -> runCatching { locator.stopLocation() };runCatching { locator.onDestroy() } }
        client=null
    }
}

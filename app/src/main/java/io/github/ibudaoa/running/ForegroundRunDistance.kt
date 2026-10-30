package io.github.ibudaoa.running

import io.github.ibudaoa.domain.PlatformDistance
import io.github.ibudaoa.maps.DisplayFix
import io.github.ibudaoa.maps.ForegroundFixGate
import kotlin.math.max
import kotlin.math.min

/** Local GPS distance only. No guide, graph, route progress or checkpoint substitution is accepted here. */
enum class DistancePhase { IDLE, RUNNING, PAUSED, INTERRUPTED, FINISHED }
internal enum class DistanceSample { IGNORED, REJECTED, ANCHOR, STATIONARY, SEGMENT }
enum class DistanceRejection { INVALID_FIX, OUT_OF_ORDER, DUPLICATE_TIME, GAP, LOCATION_JUMP, DISTANCE_ERROR }
data class ActualDistanceSummary(val phase:DistancePhase=DistancePhase.IDLE,val meters:Double=0.0,
    val segments:Int=0,val rejected:Int=0,val discontinuities:Int=0,val lastRejection:DistanceRejection?=null)
internal class ForegroundRunDistance(private val distance:PlatformDistance) {
    var summary=ActualDistanceSummary()
        private set
    private var anchor:DisplayFix?=null
    private var lastElapsed=-1L
    private var lastSdkTime=-1L
    fun start(fix:DisplayFix,nowElapsed:Long):Boolean {
        if(summary.phase!=DistancePhase.IDLE || !valid(fix,nowElapsed)) return false
        anchor=fix;lastElapsed=fix.receivedElapsedMillis;lastSdkTime=fix.sdkTimeMillis!!
        summary=ActualDistanceSummary(DistancePhase.RUNNING)
        return true
    }
    fun sample(fix:DisplayFix,nowElapsed:Long):DistanceSample {
        if(summary.phase!=DistancePhase.RUNNING) return DistanceSample.IGNORED
        if(!valid(fix,nowElapsed)) { anchor=null;reject(DistanceRejection.INVALID_FIX);return DistanceSample.REJECTED }
        if(fix.receivedElapsedMillis<lastElapsed || fix.sdkTimeMillis!!<lastSdkTime) { reject(DistanceRejection.OUT_OF_ORDER);return DistanceSample.REJECTED }
        if(fix.sdkTimeMillis==lastSdkTime) { reject(DistanceRejection.DUPLICATE_TIME);return DistanceSample.REJECTED }
        lastElapsed=fix.receivedElapsedMillis;lastSdkTime=fix.sdkTimeMillis
        val previous=anchor
        if(previous==null) { anchor=fix;return DistanceSample.ANCHOR }
        val duration=fix.sdkTimeMillis!!-previous.sdkTimeMillis!!
        if(duration>ForegroundFixGate.MAX_AGE_MS || fix.receivedElapsedMillis-previous.receivedElapsedMillis>ForegroundFixGate.MAX_AGE_MS) {
            anchor=fix;summary=summary.copy(discontinuities=summary.discontinuities+1);reject(DistanceRejection.GAP);return DistanceSample.ANCHOR
        }
        val meters=runCatching { distance.meters(previous.coordinate,fix.coordinate) }.getOrNull()
        if(meters==null || !meters.isFinite() || meters<0) { anchor=null;reject(DistanceRejection.DISTANCE_ERROR);return DistanceSample.REJECTED }
        // Local quality checks only, not platform speed/eligibility rules.
        if(meters*1000/duration>12.0) { anchor=null;reject(DistanceRejection.LOCATION_JUMP);return DistanceSample.REJECTED }
        val jitter=max(2.0,min(10.0,(previous.accuracyMeters+fix.accuracyMeters)/4.0))
        if(meters<jitter) return DistanceSample.STATIONARY // Keep anchor so multiple genuine short movements can accumulate.
        anchor=fix
        summary=summary.copy(meters=summary.meters+meters,segments=summary.segments+1,lastRejection=null)
        return DistanceSample.SEGMENT
    }
    fun pause() { if(summary.phase==DistancePhase.RUNNING) { anchor=null;summary=summary.copy(phase=DistancePhase.PAUSED) } }
    fun onBackground() {
        if(summary.phase in setOf(DistancePhase.RUNNING,DistancePhase.PAUSED)) {
            anchor=null;summary=summary.copy(phase=DistancePhase.INTERRUPTED)
        }
    }
    fun resume(fix:DisplayFix,nowElapsed:Long,platformReconciled:Boolean=false):Boolean {
        if(summary.phase!=DistancePhase.PAUSED && !(summary.phase==DistancePhase.INTERRUPTED && platformReconciled)) return false
        if(!valid(fix,nowElapsed) || fix.receivedElapsedMillis<lastElapsed || fix.sdkTimeMillis!!<=lastSdkTime) return false
        anchor=fix;lastElapsed=fix.receivedElapsedMillis;lastSdkTime=fix.sdkTimeMillis!!
        summary=summary.copy(phase=DistancePhase.RUNNING,lastRejection=null)
        return true
    }
    fun finish() { anchor=null;summary=summary.copy(phase=DistancePhase.FINISHED) }
    private fun reject(reason:DistanceRejection) { summary=summary.copy(rejected=summary.rejected+1,lastRejection=reason) }
    private fun valid(fix:DisplayFix,now:Long)=fix.accuracyMeters.isFinite() && fix.accuracyMeters>0 && fix.accuracyMeters<=50 &&
        fix.coordinate.latitude.isFinite() && fix.coordinate.longitude.isFinite() && fix.coordinate.latitude in -90.0..90.0 &&
        fix.coordinate.longitude in -180.0..180.0 && fix.sdkTimeMillis?.let { it>0 }==true &&
        fix.receivedElapsedMillis>=0 && now>=fix.receivedElapsedMillis && now-fix.receivedElapsedMillis<=ForegroundFixGate.MAX_AGE_MS
}

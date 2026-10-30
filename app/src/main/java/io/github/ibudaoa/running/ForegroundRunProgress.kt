package io.github.ibudaoa.running

import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.domain.PlatformDistance
import io.github.ibudaoa.maps.DisplayFix

/** Local observations only. Neither GPS proximity nor distance is platform-confirmed check-in evidence. */
data class RunProgressSummary(val distance:ActualDistanceSummary=ActualDistanceSummary(),val observedPoints:Int=0,
    val requiredPoints:Int=0,val elapsedSeconds:Long=0,val platformConfirmedPoints:Int=0)
internal class MeasuredRunFix(val fix:DisplayFix,val elapsedSeconds:Long,val cumulativeMeters:Double,val startsSegment:Boolean) {
    override fun toString()="MeasuredRunFix(private)"
}
internal class ObservedRunCheckpoint(val id:String,val fix:DisplayFix,val cumulativeMeters:Double) {
    override fun toString()="ObservedRunCheckpoint(private)"
}
internal class ForegroundRunProgress(points:Map<String,CampusCoordinate>,private val radiusMeters:Double,
    private val distance:PlatformDistance,private val maxTraceFixes:Int=20_000) {
    init {
        require(maxTraceFixes in 2..20_000)
        require(points.size in 2..24 && points.keys.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) })
        require(radiusMeters.isFinite() && radiusMeters>0 && radiusMeters<=10_000)
        require(points.values.all { it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
            it.longitude.isFinite() && it.longitude in -180.0..180.0 })
    }
    private val fixedPoints=points.toMap()
    private val tracker=ForegroundRunDistance(distance)
    private val observed=linkedMapOf<String,ObservedRunCheckpoint>()
    private val trace=mutableListOf<MeasuredRunFix>()
    private var measuredFix:DisplayFix?=null
    fun traceSnapshot():List<MeasuredRunFix> = trace.toList()
    fun acceptedFix():DisplayFix? = measuredFix
    fun checkpointSnapshot():List<ObservedRunCheckpoint> = observed.values.toList()
    private var startedElapsed:Long?=null
    private var lastClock=0L
    val summary:RunProgressSummary get()=RunProgressSummary(tracker.summary,observed.size,fixedPoints.size,
        startedElapsed?.let { (lastClock-it).coerceAtLeast(0)/1000 } ?: 0)
    fun start(fix:DisplayFix,nowElapsed:Long):Boolean {
        if(!tracker.start(fix,nowElapsed)) return false
        startedElapsed=nowElapsed;lastClock=nowElapsed;observe(fix);record(fix,true)
        return true
    }
    fun sample(fix:DisplayFix,nowElapsed:Long) {
        tick(nowElapsed)
        if(tracker.summary.phase!=DistancePhase.RUNNING) return
        if(trace.size>=maxTraceFixes) { background(nowElapsed);return }
        when(val decision=tracker.sample(fix,nowElapsed)) {
            DistanceSample.ANCHOR,DistanceSample.STATIONARY,DistanceSample.SEGMENT -> {
                observe(fix);record(fix,decision==DistanceSample.ANCHOR)
            }
            else -> Unit
        }
    }
    fun tick(nowElapsed:Long) {
        if(startedElapsed!=null && tracker.summary.phase!=DistancePhase.FINISHED && nowElapsed>=lastClock) lastClock=nowElapsed
    }
    fun pause(nowElapsed:Long) { tick(nowElapsed);tracker.pause();measuredFix=null }
    fun background(nowElapsed:Long) { tick(nowElapsed);tracker.onBackground();measuredFix=null }
    fun resume(fix:DisplayFix,nowElapsed:Long,platformReconciled:Boolean=false):Boolean {
        if(trace.size>=maxTraceFixes || nowElapsed<lastClock || !tracker.resume(fix,nowElapsed,platformReconciled)) return false
        tick(nowElapsed);observe(fix);record(fix,true)
        return true
    }
    fun finish(nowElapsed:Long) { tick(nowElapsed);tracker.finish();measuredFix=null }
    private fun record(fix:DisplayFix,startsSegment:Boolean) {
        measuredFix=fix
        trace.add(MeasuredRunFix(fix,summary.elapsedSeconds,tracker.summary.meters,startsSegment))
    }
    private fun observe(fix:DisplayFix) {
        for((id,point) in fixedPoints) {
            if(id in observed) continue
            val meters=runCatching { distance.meters(fix.coordinate,point) }.getOrNull() ?: continue
            if(meters.isFinite() && meters>=0 && meters<=radiusMeters)
                observed[id]=ObservedRunCheckpoint(id,fix,tracker.summary.meters)
        }
    }
}

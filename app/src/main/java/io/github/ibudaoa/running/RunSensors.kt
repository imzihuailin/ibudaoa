package io.github.ibudaoa.running

import io.github.ibudaoa.maps.DisplayFix
import io.github.ibudaoa.preview.LocalRoutePreviewEngine
import kotlinx.serialization.json.JsonArray
import kotlin.math.abs

internal enum class RunSensorKind { STEP_DETECTOR, STEP_COUNTER, ACCELEROMETER, GYROSCOPE }
enum class RunSensorPhase { IDLE, LISTENING, PAUSED, STOPPED, INTERRUPTED, UNAVAILABLE, PERMISSION }
data class RunSensorSummary(val phase:RunSensorPhase=RunSensorPhase.IDLE,val stepDetector:Boolean=false,val stepCounter:Boolean=false,
    val accelerometer:Boolean=false,val gyroscope:Boolean=false,val stepCallbacks:Int=0,val steps:Int=0,
    val accelerationCallbacks:Int=0,val gyroscopeCallbacks:Int=0,val pairedSamples:Int=0,val rejected:Int=0,
    val completed:Boolean=false) {
    val sampled:Boolean get()=(stepDetector || stepCounter) && stepCallbacks>0 && accelerationCallbacks>0 && gyroscopeCallbacks>0 && pairedSamples>0
}
internal class MeasuredRunSensor(val wallTimeMillis:Long,val fix:DisplayFix,val acceleration:List<Float>,val rotation:List<Float>) {
    override fun toString()="MeasuredRunSensor(private)"
}
/** Event timestamps and values come directly from Android; pauses and missing callbacks are never synthesized. */
internal class RunSensorCore(private val startedNanos:Long,private val maxSamples:Int=20_000) {
    private var active=true
    private var paused=false
    private val lastTimes=mutableMapOf<RunSensorKind,Long>()
    private var acceleration:Pair<Long,List<Float>>?=null
    private var rotation:Pair<Long,List<Float>>?=null
    private var lastPair=-1L
    private var counter:Long?=null
    private val bins=mutableMapOf<Int,Int>()
    private val samples=mutableListOf<MeasuredRunSensor>()
    var summary=RunSensorSummary(RunSensorPhase.LISTENING)
        private set
    init { require(startedNanos>=0 && maxSamples in 1..20_000) }
    fun hardware(detector:Boolean,counter:Boolean,accel:Boolean,gyro:Boolean) {
        summary=summary.copy(stepDetector=detector,stepCounter=counter,accelerometer=accel,gyroscope=gyro)
    }
    fun accept(kind:RunSensorKind,values:FloatArray,eventNanos:Long,nowNanos:Long,wallMillis:Long,fix:DisplayFix?) {
        if(!active || paused) return
        fun reject() { summary=summary.copy(rejected=(summary.rejected+1).coerceAtMost(1_000_000)) }
        if(eventNanos<startedNanos || eventNanos>nowNanos || nowNanos-eventNanos>2_000_000_000 ||
            eventNanos<=(lastTimes[kind] ?: -1)) { reject();return }
        if(values.isEmpty() || values.any { !it.isFinite() }) { reject();return }
        lastTimes[kind]=eventNanos
        if(kind==RunSensorKind.STEP_COUNTER || kind==RunSensorKind.STEP_DETECTOR) {
            if(summary.stepCallbacks>=1_000_000 || summary.steps>=100_000) { stop(true);return }
            val delta=if(kind==RunSensorKind.STEP_DETECTOR) {
                if(values[0]!=1f) { reject();return };1
            } else {
                val value=values[0].toLong()
                if(values[0]<0 || values[0].toDouble()!=value.toDouble() || value>1_000_000_000) { reject();return }
                val previous=counter;counter=value
                if(previous==null || value<previous)0 else (value-previous).takeIf { it<=1000 }?.toInt() ?: run { reject();return }
            }
            val minute=((eventNanos-startedNanos)/60_000_000_000).toInt()
            if(minute !in 0..1439) { stop(true);return }
            if(summary.steps.toLong()+delta>100_000 || (bins[minute] ?: 0)+delta>1000) { stop(true);return }
            bins[minute]=(bins[minute] ?: 0)+delta
            summary=summary.copy(stepCallbacks=summary.stepCallbacks+1,steps=summary.steps+delta)
            return
        }
        if(values.size<3 || values.take(3).any { abs(it)>200 }) { reject();return }
        val vector=values.take(3)
        when(kind) {
            RunSensorKind.ACCELEROMETER -> {
                acceleration=eventNanos to vector
                summary=summary.copy(accelerationCallbacks=(summary.accelerationCallbacks+1).coerceAtMost(1_000_000))
            }
            RunSensorKind.GYROSCOPE -> {
                rotation=eventNanos to vector
                summary=summary.copy(gyroscopeCallbacks=(summary.gyroscopeCallbacks+1).coerceAtMost(1_000_000))
            }
            else -> Unit
        }
        val a=acceleration ?: return;val g=rotation ?: return
        if(abs(a.first-g.first)>200_000_000 || a.first<=lastPair || g.first<=lastPair ||
            eventNanos-lastPair<100_000_000 || !LocalRoutePreviewEngine.fresh(fix,nowNanos/1_000_000) || wallMillis<=0) return
        if(samples.size>=maxSamples) { stop(true);return }
        val eventWall=wallMillis-(nowNanos-eventNanos)/1_000_000
        if(eventWall<=0) { reject();return }
        samples.add(MeasuredRunSensor(eventWall,fix!!,a.second,g.second));lastPair=maxOf(a.first,g.first)
        summary=summary.copy(pairedSamples=samples.size)
        acceleration=null;rotation=null
    }
    fun pause() { if(active) { paused=true;counter=null;acceleration=null;rotation=null;summary=summary.copy(phase=RunSensorPhase.PAUSED) } }
    fun resume() { if(active && paused) { paused=false;summary=summary.copy(phase=RunSensorPhase.LISTENING) } }
    fun stop(interrupted:Boolean=false) {
        active=false;counter=null;acceleration=null;rotation=null
        summary=summary.copy(phase=if(interrupted)RunSensorPhase.INTERRUPTED else RunSensorPhase.STOPPED)
    }
    fun measurements(elapsedSeconds:Long):RunFinishMeasurements {
        require(elapsedSeconds in 1..86_400 && summary.stepCallbacks>0)
        val base=((elapsedSeconds+59)/60).toInt()
        val count=maxOf(base,(bins.keys.maxOrNull() ?: -1)+1)
        require(count<=base+(if(elapsedSeconds%60==0L)1 else 0) && bins.keys.all { it<count })
        return RunFinishMeasurements(summary.steps,(0 until count).map { bins[it] ?: 0 },JsonArray(emptyList()))
    }
    fun snapshot():List<MeasuredRunSensor> = samples.toList()
    fun discardSamples() { samples.clear();acceleration=null;rotation=null }
}

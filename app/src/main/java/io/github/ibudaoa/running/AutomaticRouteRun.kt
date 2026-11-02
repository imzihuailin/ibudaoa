package io.github.ibudaoa.running

import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.domain.PlatformDistance
import io.github.ibudaoa.roads.FixedPointRoute
import kotlin.math.ceil

enum class RunFinishStage { VALIDATION, STORAGE_REQUEST, STORAGE_DECODE, FILE_BUILD, TRACK_UPLOAD, MOTION_UPLOAD, FINISH_REQUEST, FINISH_RESPONSE }
enum class AutomaticVerificationState { WAITING, VERIFYING, MANUAL }
enum class AutomaticRunPhase { IDLE, STARTING, RUNNING, PAUSED, INTERRUPTED, FINISHING, AWAITING_VERDICT, VALID, INVALID, FAILED }
data class AutomaticRunSummary(val phase:AutomaticRunPhase=AutomaticRunPhase.IDLE,val meters:Double=0.0,
    val targetMeters:Double=0.0,val elapsedSeconds:Long=0,val visitedPoints:Int=0,val requiredPoints:Int=0,
    val platformStarted:Boolean=false,val recordBound:Boolean=false,val recordVerified:Boolean=false,val problem:String?=null,val serverPointCount:Int?=null,val serverStatus:Int?=null,val remainingSeconds:Long?=null,val pendingWrite:String?=null,val finishStage:RunFinishStage?=null, val identityChecks:Map<String,RecordFieldCheck> = emptyMap(),
    val ruleProblems:Set<RunCompletionProblem> = emptySet(),val acknowledgedPoints:Int=0,val verification:AutomaticVerificationState=AutomaticVerificationState.WAITING) {
    val manualConfirmationAvailable:Boolean get()=verification==AutomaticVerificationState.MANUAL &&
        phase in setOf(AutomaticRunPhase.AWAITING_VERDICT,AutomaticRunPhase.INTERRUPTED)
    val blocksExternalUi:Boolean get()=phase in setOf(AutomaticRunPhase.STARTING,AutomaticRunPhase.RUNNING,AutomaticRunPhase.PAUSED,AutomaticRunPhase.FINISHING)
    val progressFraction:Float get()=if(targetMeters>0) (meters/targetMeters).coerceIn(0.0,1.0).toFloat() else 0f
}
/** Generated route positions, deliberately distinct from device GPS and sensor observations. */
internal class AutomaticRunSample(val coordinate:CampusCoordinate,val activeMillis:Long,val elapsedMillis:Long,val meters:Double,val speed:Double) {
    override fun toString()="AutomaticRunSample(generated)"
}
internal class AutomaticCheckpoint(val id:String,val sample:AutomaticRunSample) {
    override fun toString()="AutomaticCheckpoint(generated)"
}
internal class AutomaticRouteRun(route:FixedPointRoute,points:Map<String,CampusCoordinate>,private val radius:Double,
    private val metric:PlatformDistance,paceSecondsPerKm:Int) {
    private val fixedPoints=points.toMap()
    private val polyline=mutableListOf<CampusCoordinate>()
    private val cumulative=mutableListOf(0.0)
    private val samples=mutableListOf<AutomaticRunSample>()
    private val visits=linkedMapOf<String,AutomaticCheckpoint>()
    private var lastClock=-1L
    private var elapsed=0L
    private var active=0L
    private var nextSample=500L
    private var completed:AutomaticRunSnapshot?=null
    val totalMeters:Double
    val durationMillis:Long
    @Volatile var phase=AutomaticRunPhase.IDLE
        private set
    init {
        require(points.keys==route.selectedIds.toSet() && points.size in 2..8 && radius.isFinite() && radius>0 && paceSecondsPerKm>0)
        require(route.includesReviewedOrigin && route.legs.isNotEmpty())
        for(leg in route.legs) {
            require(leg.points.size>=2)
            if(polyline.isNotEmpty()) require(metric.meters(polyline.last(),leg.points.first())<=0.01) { "Automatic route has an unreviewed join" }
            for(p in leg.points) {
                require(p.latitude.isFinite() && p.longitude.isFinite() && p.latitude in -90.0..90.0 && p.longitude in -180.0..180.0)
                if(polyline.lastOrNull()==p)continue
                if(polyline.isNotEmpty()) {
                    val length=metric.meters(polyline.last(),p)
                    require(length.isFinite() && length>=0)
                    cumulative.add(cumulative.last()+length)
                }
                polyline.add(p)
            }
        }
        require(polyline.size in 2..100_000)
        totalMeters=cumulative.last()
        require(totalMeters>0 && totalMeters>=route.minimumTargetMeters && totalMeters<=route.maximumTargetMeters)
        durationMillis=ceil(totalMeters*paceSecondsPerKm/1000-0.000000001).toLong()*1000
        require(durationMillis in 1000..8_000_000 && durationMillis/500+2<=20_000)
    }
    @get:Synchronized val elapsedMillis get()=elapsed
    @get:Synchronized val activeMillis get()=active
    @Synchronized fun trace():List<AutomaticRunSample> = samples.toList()
    @Synchronized fun checkpoints():List<AutomaticCheckpoint> = visits.values.toList()
    @Synchronized fun current():AutomaticRunSample? = samples.lastOrNull()
    @Synchronized fun checkpointIdsAt(sample:AutomaticRunSample):Set<String> = fixedPoints.filter { (id,p) ->
        id in visits && metric.meters(sample.coordinate,p)<=radius+0.000001
    }.keys.toSet()
    @Synchronized fun start(now:Long) {
        check(phase==AutomaticRunPhase.IDLE && now>=0)
        lastClock=now;phase=AutomaticRunPhase.RUNNING;append(0)
    }
    @Synchronized fun advance(now:Long) {
        require(now>=lastClock)
        if(phase !in setOf(AutomaticRunPhase.RUNNING,AutomaticRunPhase.PAUSED))return
        val dt=now-lastClock;lastClock=now;elapsed+=dt
        if(phase==AutomaticRunPhase.PAUSED)return
        val rawActive=active+dt
        active=rawActive.coerceAtMost(durationMillis)
        while(nextSample<=active && nextSample<durationMillis) { append(nextSample,elapsed-(rawActive-nextSample));nextSample+=500 }
        if(active==durationMillis) {
            elapsed-=rawActive-durationMillis
            append(durationMillis,elapsed);phase=AutomaticRunPhase.FINISHING
        }
    }
    @Synchronized fun pause(now:Long) {
        if(phase==AutomaticRunPhase.RUNNING) {
            advance(now)
            if(phase==AutomaticRunPhase.RUNNING) { append(active);phase=AutomaticRunPhase.PAUSED }
        }
    }
    @Synchronized fun resume(now:Long) {
        check(phase==AutomaticRunPhase.PAUSED);advance(now)
        append(active,elapsed,stationary=true);phase=AutomaticRunPhase.RUNNING
    }
    @Synchronized fun interrupt(now:Long) {
        if(phase in setOf(AutomaticRunPhase.RUNNING,AutomaticRunPhase.PAUSED))advance(now)
        if(phase !in setOf(AutomaticRunPhase.IDLE))phase=AutomaticRunPhase.INTERRUPTED
    }
    @Synchronized fun freeze(now:Long,context:RunRequestContext) {
        if(completed!=null) { completed!!.validate(context);return }
        check(phase in setOf(AutomaticRunPhase.RUNNING,AutomaticRunPhase.PAUSED,AutomaticRunPhase.FINISHING))
        if(phase in setOf(AutomaticRunPhase.RUNNING,AutomaticRunPhase.PAUSED))advance(now)
        append(active,elapsed,stationary=phase==AutomaticRunPhase.PAUSED)
        completed=AutomaticRunSnapshot(samples.toList(),visits.values.toList(),fixedPoints.keys.toSet(),elapsed,active,context)
        phase=AutomaticRunPhase.FINISHING
    }
    @Synchronized fun completion():AutomaticRunSnapshot = completed ?: error("Run is not frozen")
    @Synchronized fun inspection():AutomaticRunSnapshot = completed ?: AutomaticRunSnapshot(samples,visits.values.toList(),fixedPoints.keys,elapsed,active)
    private fun append(at:Long,wallElapsed:Long=(elapsed-(active-at)).coerceAtLeast(0),stationary:Boolean=false) {
        if(samples.lastOrNull()?.let { it.activeMillis==at && it.elapsedMillis==wallElapsed }==true)return
        val meters=totalMeters*at/durationMillis
        val index=(cumulative.binarySearch(meters).let { if(it>=0)it else -it-2 }).coerceIn(0,polyline.lastIndex-1)
        val length=cumulative[index+1]-cumulative[index]
        val fraction=if(length>0)((meters-cumulative[index])/length).coerceIn(0.0,1.0) else 0.0
        val a=polyline[index];val b=polyline[index+1]
        val p=CampusCoordinate(a.latitude+(b.latitude-a.latitude)*fraction,a.longitude+(b.longitude-a.longitude)*fraction)
        val sample=AutomaticRunSample(p,at,wallElapsed,meters,if(stationary)0.0 else totalMeters*1000/durationMillis)
        samples.add(sample)
        for((id,coordinate) in fixedPoints) if(id !in visits && metric.meters(p,coordinate)<=radius+0.000001)visits[id]=AutomaticCheckpoint(id,sample)
    }
}

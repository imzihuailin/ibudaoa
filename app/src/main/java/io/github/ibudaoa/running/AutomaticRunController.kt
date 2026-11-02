package io.github.ibudaoa.running

import io.github.ibudaoa.capture.AutomaticRunGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*

internal sealed interface AutomaticFinishReview {
    data object Allowed : AutomaticFinishReview
    data class Incomplete(val problems:Set<RunCompletionProblem>) : AutomaticFinishReview
    data object Unavailable : AutomaticFinishReview
}

internal class AutomaticRunController(val ledger:RunTaskLedger,val timeline:AutomaticRouteRun,
    private val gateway:AutomaticRunGateway,private val gyroRequired:Boolean,private val bound:()->Boolean,
    private val preparationCurrent:()->Boolean,private val clock:()->Long) {
    @Volatile private var foreground=true
    @Volatile private var starting=false
    var persist:()->Unit = {}
    private val operations=Mutex()
    private var lastUpload=-1L
    private val acknowledgedIds=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val requiredPoints=ledger.requestContext().points.size
    @Volatile private var problem:String?=null
    @Volatile private var desiredPause=false
    @Volatile private var finishing=false
    @Volatile private var ruleProblems:Set<RunCompletionProblem> = emptySet()
    @get:Synchronized val summary:AutomaticRunSummary get() {
        val task=ledger.summary
        val phase=when {
            task.phase==RunTaskPhase.VALID -> AutomaticRunPhase.VALID
            task.phase==RunTaskPhase.INVALID -> AutomaticRunPhase.INVALID
            task.needsReconciliation || !foreground -> AutomaticRunPhase.INTERRUPTED
            finishing -> AutomaticRunPhase.FINISHING
            task.phase==RunTaskPhase.AWAITING_VERDICT -> AutomaticRunPhase.AWAITING_VERDICT
            starting -> AutomaticRunPhase.STARTING
            timeline.phase == AutomaticRunPhase.IDLE -> if(problem == null) AutomaticRunPhase.STARTING else AutomaticRunPhase.FAILED
            else -> timeline.phase
        }
        return AutomaticRunSummary(phase,timeline.current()?.meters ?: 0.0,timeline.totalMeters,timeline.elapsedMillis/1000,
            timeline.checkpoints().size,requiredPoints,task.platformStarted,task.recordBound,task.recordVerified,problem,ledger.serverPointCount(),ledger.serverRecordStatus(),
            ((timeline.durationMillis-timeline.activeMillis).coerceAtLeast(0)+999)/1000,ledger.pendingWrite()?.name,ledger.finishStage(),ledger.identityChecks(),ruleProblems,acknowledgedIds.size)
    }
    fun saveExecution(config:JsonObject,requirements:RunRuntimeRequirements) {
        ledger.saveExecution(buildJsonObject {
            put("timeline",timeline.checkpoint());put("config",config);put("requirements",requirements.checkpoint())
            put("gyro",gyroRequired);put("lastUpload",lastUpload)
            put("ack",JsonArray(acknowledgedIds.map(::JsonPrimitive)))
        })
    }
    fun restoreExecution(o:JsonObject) {
        lastUpload=o.getValue("lastUpload").jsonPrimitive.long
        acknowledgedIds.addAll(o.getValue("ack").jsonArray.map { it.jsonPrimitive.content })
        desiredPause=true
    }
    suspend fun start():Boolean {
        if(!operations.tryLock())return false
        return try {
            startLocked().also { success ->
                if(!success && timeline.phase == AutomaticRunPhase.IDLE && problem == null) problem="START_REJECTED"
            }
        } finally { operations.unlock() }
    }
    private suspend fun startLocked():Boolean {
        if(!foreground || starting || ledger.summary.phase!=RunTaskPhase.PREPARED || !bound() || !preparationCurrent())return false
        gateway.preparationProblem()?.let { problem=it;return false }
        starting=true
        try {
            if(!send(RunWrite.SELECT_ZONE))return false
            if(!foreground || !bound() || !preparationCurrent()) { problem="PREPARATION_CHANGED";interrupt();return false }
            if(!send(RunWrite.REGISTER))return false
            synchronized(this) {
                if(!foreground || !bound() || ledger.summary.phase!=RunTaskPhase.RUNNING)return false
                if(!gateway.registrationTimeAccepted(ledger.requestContext().registeredStart!!)) {
                    problem="REGISTRATION_TIME_MISMATCH";interrupt();return false
                }
                timeline.start(clock())
            }
            return true
        } finally { starting=false }
    }
    suspend fun tick() {
        if(!operations.tryLock())return
        try { tickLocked() } finally { operations.unlock() }
    }
    private suspend fun tickLocked() {
        synchronized(this) {
            if(!foreground || !bound()) { interrupt();return }
            if(ledger.summary.needsReconciliation)return
            if(timeline.phase !in setOf(AutomaticRunPhase.RUNNING,AutomaticRunPhase.PAUSED,AutomaticRunPhase.FINISHING))return
            timeline.advance(clock())
        }
        if(timeline.phase==AutomaticRunPhase.PAUSED)updateRuleProblems()
        if(timeline.phase==AutomaticRunPhase.FINISHING) { finishLocked();return }
        applyPauseIfIdle()
        val current=timeline.current()
        if(timeline.phase==AutomaticRunPhase.RUNNING && current!=null && (timeline.checkpointIdsAt(current).any { it !in acknowledgedIds } ||
                lastUpload<0 || timeline.elapsedMillis-lastUpload>=ledger.locationIntervalSeconds()*1000L)) {
            reportLocation(current)
            applyPauseIfIdle()
        }
    }
    @Synchronized fun pause() {
        if(!foreground || finishing)return
        desiredPause=true;timeline.pause(clock())
        if(ledger.summary.phase==RunTaskPhase.RUNNING && !ledger.summary.writePending)ledger.pause()
        if(timeline.phase==AutomaticRunPhase.PAUSED)updateRuleProblems()
    }
    @Synchronized fun resume():Boolean {
        if(!foreground || !bound() || timeline.phase!=AutomaticRunPhase.PAUSED || ledger.summary.needsReconciliation)return false
        desiredPause=false
        if(ledger.summary.phase==RunTaskPhase.PAUSED)ledger.resume()
        timeline.resume(clock());ruleProblems=emptySet();return true
    }
    suspend fun finish():Boolean {
        if(!operations.tryLock())return false
        return try { finishLocked() } finally { operations.unlock() }
    }
    /**
     * Reviews the current run against the current rules without writing or freezing it.
     * The endpoint report state is excluded: the finish path reports the endpoint itself.
     */
    suspend fun reviewFinish():AutomaticFinishReview {
        if(!operations.tryLock())return AutomaticFinishReview.Unavailable
        return try {
            synchronized(this) {
                val task=ledger.summary
                if(finishing || !foreground || !bound() || task.writePending ||
                    task.phase !in setOf(RunTaskPhase.RUNNING,RunTaskPhase.PAUSED))
                    return@synchronized AutomaticFinishReview.Unavailable
                try { updateRuleProblems() }
                catch(_:Exception) { return@synchronized AutomaticFinishReview.Unavailable }
                val blocking=ruleProblems-RunCompletionProblem.CHECKPOINT_REPORTS
                if(blocking.isEmpty())AutomaticFinishReview.Allowed else AutomaticFinishReview.Incomplete(blocking)
            }
        } finally { operations.unlock() }
    }
    /** Drops a run that was never submitted, so no record is kept locally or sent to the platform. */
    @Synchronized fun discardUnfinished():Boolean {
        if(!foreground || finishing || !ledger.canDiscardUnfinished())return false
        return try {
            timeline.interrupt(clock())
            ledger.discardUnfinished()
            true
        } catch(_:Exception) { false }
    }
    private suspend fun finishLocked():Boolean {
        val snapshot=synchronized(this) {
            if(finishing || !foreground || !bound() || ledger.summary.writePending || ledger.summary.phase !in setOf(RunTaskPhase.RUNNING,RunTaskPhase.PAUSED))return false
            timeline.freeze(clock(),ledger.requestContext());finishing=true
            timeline.completion()
        }
        try {
            try { gateway.validateCompletion(ledger.requestContext(),snapshot) }
            catch(e:RunMaterialException) {
                problem="MATERIAL_${e.problem.name}";ledger.recordMaterialFailure(e.problem);interrupt();return false
            }
            // One current endpoint report only. Never flush historical visits as current positions.
            val current=timeline.current()!!
            if(timeline.checkpointIdsAt(current).any { it !in acknowledgedIds } && !reportLocation(current))return false
            updateRuleProblems()
            ledger.recordCompletionChecks(ruleProblems,acknowledgedIds.size)
            if(!send(RunWrite.FINISH))return false
            if(!foreground || !bound())return false
            if(gyroRequired) {
                val record=ledger.expectedRecord();val permit=ledger.begin(RunWrite.GYROSCOPE)
                try {
                    val body=gateway.associate(ledger.requestContext(),record)
                    try { if(!ledger.accept(permit,body))return false } finally { body.fill(0) }
                } catch(e:CancellationException) { ledger.uncertain(permit);throw e }
                catch(_:Exception) { ledger.uncertain(permit);problem="ASSOCIATION_UNCERTAIN";return false }
            }
            // The ViewModel owns the bounded verification window for live and restored tasks.
            return true
        } finally { finishing=false }
    }
    suspend fun query():Boolean {
        if(!operations.tryLock())return false
        return try { queryLocked() } finally { operations.unlock() }
    }
    private suspend fun queryLocked():Boolean {
        if(!foreground || !bound() || !ledger.summary.recordBound)return false
        val body=try { gateway.record(ledger.requestContext(),ledger.expectedRecord()) }
            catch(e:CancellationException) { throw e } catch(_:Exception) { problem="RECORD_QUERY_FAILED";return false }
        return try {
            if(!foreground || !bound())return false
            val result=ledger.verifyRecord(body)
            problem=when(result.verdict) {
                RunRecordVerdict.VALID,RunRecordVerdict.INVALID -> null
                else -> "RECORD_${result.verdict.name}"
            }
            result.platformValid
        } finally { body.fill(0) }
    }
    @Synchronized fun interrupt() {
        foreground=false
        if(timeline.phase!=AutomaticRunPhase.IDLE)timeline.interrupt(clock())
        ledger.background()
    }
    @Synchronized fun returned() { foreground=true;ledger.foreground() }
    @Synchronized private fun applyPauseIfIdle() {
        if(foreground && desiredPause && ledger.summary.phase==RunTaskPhase.RUNNING && !ledger.summary.writePending)ledger.pause()
    }
    private fun updateRuleProblems() {
        val snapshot=timeline.inspection()
        ruleProblems=gateway.completionProblems(ledger.requestContext(),snapshot)+
            (if(acknowledgedIds.containsAll(snapshot.ids))emptySet() else setOf(RunCompletionProblem.CHECKPOINT_REPORTS))
    }
    private suspend fun reportLocation(point:AutomaticRunSample):Boolean {
        val ids=timeline.checkpointIdsAt(point)
        return send(RunWrite.LOCATION,point).also { ok ->
            if(ok) { acknowledgedIds.addAll(ids);lastUpload=point.elapsedMillis;persist() }
        }
    }
    private suspend fun send(kind:RunWrite,location:AutomaticRunSample?=null):Boolean {
        if(!foreground || !bound())return false
        if(timeline.current()!=null)persist()
        val permit=ledger.begin(kind)
        return try {
            val body=gateway.write(kind,ledger.requestContext(),timeline,ledger::markFinishStage,location)
            try { ledger.accept(permit,body).also { if(!it)problem="WRITE_RESULT_UNCERTAIN" } } finally { body.fill(0) }
        } catch(e:CancellationException) { ledger.uncertain(permit);throw e }
        catch(e:RunMaterialException) { ledger.uncertain(permit);problem="MATERIAL_${e.problem.name}";false }
        catch(_:Exception) { ledger.uncertain(permit);problem="WRITE_RESULT_UNCERTAIN";false }
    }
}

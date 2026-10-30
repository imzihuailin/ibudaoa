package io.github.ibudaoa.running

import io.github.ibudaoa.capture.AutomaticRunGateway
import kotlinx.coroutines.CancellationException

internal class AutomaticRunController(val ledger:RunTaskLedger,val timeline:AutomaticRouteRun,
    private val gateway:AutomaticRunGateway,private val gyroRequired:Boolean,private val bound:()->Boolean,
    private val preparationCurrent:()->Boolean,private val clock:()->Long) {
    @Volatile private var foreground=true
    @Volatile private var starting=false
    private var lastUpload=-1L
    private var uploadedVisits=0
    private val requiredPoints=ledger.requestContext().points.size
    private var problem:String?=null
    @Volatile private var desiredPause=false
    @Volatile private var finishing=false
    val summary:AutomaticRunSummary get() {
        val task=ledger.summary
        val phase=when {
            task.phase==RunTaskPhase.VALID -> AutomaticRunPhase.VALID
            task.phase==RunTaskPhase.INVALID -> AutomaticRunPhase.INVALID
            task.needsReconciliation || !foreground -> AutomaticRunPhase.INTERRUPTED
            finishing -> AutomaticRunPhase.FINISHING
            task.phase==RunTaskPhase.AWAITING_VERDICT -> AutomaticRunPhase.AWAITING_VERDICT
            starting -> AutomaticRunPhase.STARTING
            else -> timeline.phase
        }
        return AutomaticRunSummary(phase,timeline.current()?.meters ?: 0.0,timeline.totalMeters,timeline.elapsedMillis/1000,
            timeline.checkpoints().size,requiredPoints,task.platformStarted,task.recordBound,task.recordVerified,problem,ledger.serverPointCount(),ledger.serverRecordStatus(),
            ((timeline.durationMillis-timeline.activeMillis).coerceAtLeast(0)+999)/1000,ledger.pendingWrite()?.name,ledger.finishStage())
    }
    suspend fun start():Boolean {
        if(starting || ledger.summary.phase!=RunTaskPhase.PREPARED || !bound() || !preparationCurrent())return false
        starting=true
        try {
            if(!send(RunWrite.SELECT_ZONE))return false
            if(!bound() || !preparationCurrent()) { interrupt();return false }
            if(!send(RunWrite.REGISTER))return false
            if(!foreground || !bound() || ledger.summary.phase!=RunTaskPhase.RUNNING)return false
            timeline.start(clock());return true
        } finally { starting=false }
    }
    suspend fun tick() {
        if(!foreground || !bound()) { interrupt();return }
        if(ledger.summary.needsReconciliation)return
        if(timeline.phase !in setOf(AutomaticRunPhase.RUNNING,AutomaticRunPhase.PAUSED,AutomaticRunPhase.FINISHING))return
        timeline.advance(clock())
        if(timeline.phase==AutomaticRunPhase.FINISHING) { finish();return }
        if(desiredPause && ledger.summary.phase==RunTaskPhase.RUNNING && !ledger.summary.writePending)ledger.pause()
        if(timeline.phase==AutomaticRunPhase.RUNNING && (timeline.checkpoints().size>uploadedVisits || lastUpload<0 || timeline.elapsedMillis-lastUpload>=ledger.locationIntervalSeconds()*1000L)) {
            if(send(RunWrite.LOCATION)) { lastUpload=timeline.elapsedMillis;uploadedVisits=minOf(timeline.checkpoints().size,uploadedVisits+1) }
            if(desiredPause && ledger.summary.phase==RunTaskPhase.RUNNING && !ledger.summary.writePending)ledger.pause()
        }
    }
    fun pause() {
        if(!foreground || finishing)return
        desiredPause=true;timeline.pause(clock())
        if(ledger.summary.phase==RunTaskPhase.RUNNING && !ledger.summary.writePending)ledger.pause()
    }
    fun resume():Boolean {
        if(!foreground || !bound() || timeline.phase!=AutomaticRunPhase.PAUSED || ledger.summary.needsReconciliation)return false
        desiredPause=false
        if(ledger.summary.phase==RunTaskPhase.PAUSED)ledger.resume()
        timeline.resume(clock());return true
    }
    suspend fun finish():Boolean {
        if(finishing || !foreground || !bound() || ledger.summary.writePending || ledger.summary.phase !in setOf(RunTaskPhase.RUNNING,RunTaskPhase.PAUSED))return false
        timeline.freeze(clock());finishing=true
        try {
            while(uploadedVisits<timeline.checkpoints().size) {
                if(!send(RunWrite.LOCATION))return false
                uploadedVisits++
            }
            timeline.freeze(clock())
            if(!send(RunWrite.FINISH))return false
            if(gyroRequired) {
                val record=ledger.expectedRecord();val permit=ledger.begin(RunWrite.GYROSCOPE)
                try {
                    val body=gateway.associate(ledger.requestContext(),record)
                    try { if(!ledger.accept(permit,body))return false } finally { body.fill(0) }
                } catch(e:CancellationException) { ledger.uncertain(permit);throw e }
                catch(_:Exception) { ledger.uncertain(permit);problem="ASSOCIATION_UNCERTAIN";return false }
            }
            return query()
        } finally { finishing=false }
    }
    suspend fun query():Boolean {
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
    fun interrupt() {
        foreground=false
        if(timeline.phase!=AutomaticRunPhase.IDLE)timeline.interrupt(clock())
        ledger.background()
    }
    fun returned() { foreground=true;ledger.foreground() }
    private suspend fun send(kind:RunWrite):Boolean {
        if(!foreground || !bound())return false
        val permit=ledger.begin(kind)
        return try {
            val body=gateway.write(kind,ledger.requestContext(),timeline,ledger::markFinishStage)
            try { ledger.accept(permit,body).also { if(!it)problem="WRITE_RESULT_UNCERTAIN" } } finally { body.fill(0) }
        } catch(e:CancellationException) { ledger.uncertain(permit);throw e }
        catch(_:Exception) { ledger.uncertain(permit);problem="WRITE_RESULT_UNCERTAIN";false }
    }
}

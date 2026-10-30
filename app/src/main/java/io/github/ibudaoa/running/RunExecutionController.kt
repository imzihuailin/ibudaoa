package io.github.ibudaoa.running

import io.github.ibudaoa.maps.DisplayFix
import io.github.ibudaoa.preview.LocalRoutePreviewEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** Private data needed by a future fixed-endpoint protocol adapter. No token or route-generated GPS. */
internal class RunRequestContext(val account:Long,val school:Long,val term:Long,val zone:Long,
    points:List<String>,val registeredStart:Long?,val registrationProof:String?) {
    val points=points.toList()
    override fun toString()="RunRequestContext(private)"
}
internal interface RunExecutionGateway {
    /** Exactly one attempt; implementation must not retry, follow redirects or log request/response bodies. */
    suspend fun write(operation:RunWrite,context:RunRequestContext,fix:DisplayFix?,progress:RunProgressSummary,trace:List<MeasuredRunFix>,checkpoints:List<ObservedRunCheckpoint>):ByteArray
    suspend fun record(expected:ExpectedRunRecord):ByteArray
}
data class RunExecutionSummary(val task:RunTaskSummary,val progress:RunProgressSummary)

/** Serializes user commands. Production UI remains disabled until the complete protocol adapter exists. */
internal class RunExecutionController(private val ledger:RunTaskLedger,private val progress:ForegroundRunProgress,
    private val gateway:RunExecutionGateway,private val currentAccount:()->Long?,private val preparationCurrent:()->Boolean,
    private val latestFix:()->DisplayFix?,private val clock:()->Long) {
    private val command=Mutex()
    private var foreground=true
    val summary get()=RunExecutionSummary(ledger.summary,progress.summary)
    private fun bound()=foreground && currentAccount()==ledger.requestContext().account
    private fun freshFix():DisplayFix?=latestFix()?.takeIf {
        LocalRoutePreviewEngine.fresh(it,clock()) && it.sdkTimeMillis?.let { time -> time>0 }==true
    }
    suspend fun start():Boolean=serialized {
        if(!bound() || !preparationCurrent() || freshFix()==null ||
            ledger.summary.phase !in setOf(RunTaskPhase.PREPARED,RunTaskPhase.ZONE_SELECTED)) return@serialized false
        if(ledger.summary.phase==RunTaskPhase.PREPARED && !send(RunWrite.SELECT_ZONE,null)) return@serialized false
        if(!bound() || !preparationCurrent() || freshFix()==null) { background();return@serialized false }
        if(!send(RunWrite.REGISTER,null)) return@serialized false
        val fix=freshFix()
        if(!bound() || fix==null || ledger.summary.phase!=RunTaskPhase.RUNNING || !progress.start(fix,clock())) {
            background();return@serialized false
        }
        true
    }
    fun sample(fix:DisplayFix) {
        if(!bound()) { background();return }
        if(ledger.summary.phase==RunTaskPhase.RUNNING) {
            progress.sample(fix,clock())
            if(progress.summary.distance.phase==DistancePhase.INTERRUPTED) background()
        }
    }
    fun tick() { progress.tick(clock()) }
    fun pause():Boolean {
        if(!bound() || ledger.summary.phase!=RunTaskPhase.RUNNING || ledger.summary.writePending) return false
        progress.pause(clock())
        return try { ledger.pause();true } catch(_:Exception) { progress.background(clock());false }
    }
    fun resume():Boolean {
        if(!bound() || ledger.summary.phase!=RunTaskPhase.PAUSED) return false
        val fix=freshFix() ?: return false
        if(!progress.resume(fix,clock())) return false
        return try { ledger.resume();true } catch(_:Exception) { progress.background(clock());false }
    }
    fun background():Boolean {
        foreground=false;progress.background(clock())
        return try { ledger.background();true } catch(_:Exception) { false }
    }
    fun foreground() { foreground=true;ledger.foreground() } // Deliberately does not resume.
    suspend fun uploadLocation():Boolean=serialized {
        if(!bound() || ledger.summary.phase!=RunTaskPhase.RUNNING) return@serialized false
        val fix=progress.acceptedFix()?.takeIf { LocalRoutePreviewEngine.fresh(it,clock()) } ?: return@serialized false
        send(RunWrite.LOCATION,fix)
    }
    suspend fun finish():Boolean=serialized {
        if(!bound() || ledger.summary.phase !in setOf(RunTaskPhase.RUNNING,RunTaskPhase.PAUSED)) return@serialized false
        progress.pause(clock())
        if(!send(RunWrite.FINISH,null)) return@serialized false
        progress.finish(clock())
        ledger.summary.phase==RunTaskPhase.AWAITING_VERDICT
    }
    suspend fun verifyRecord():Boolean=serialized {
        if(!bound() || ledger.summary.phase!=RunTaskPhase.AWAITING_VERDICT) return@serialized false
        val response=try { gateway.record(ledger.expectedRecord()) } catch(e:CancellationException) { throw e } catch(_:Exception) { return@serialized false }
        try {
            if(!bound()) return@serialized false
            ledger.verifyRecord(response).platformValid
        } finally { response.fill(0) }
    }
    private suspend fun send(operation:RunWrite,fix:DisplayFix?):Boolean {
        if(!bound()) return false
        val permit=try { ledger.begin(operation) } catch(_:Exception) { progress.background(clock());return false }
        try {
            if(!bound()) { ledger.uncertain(permit);progress.background(clock());return false }
            val response=gateway.write(operation,ledger.requestContext(),fix,progress.summary,progress.traceSnapshot(),progress.checkpointSnapshot())
            return try {
                // Preserve an acknowledged side effect for the old account, never continue it under a new account.
                if(!bound()) { foreground=false;progress.background(clock());ledger.background() }
                ledger.accept(permit,response).also { if(!it)progress.background(clock()) }
            } finally { response.fill(0) }
        } catch(e:CancellationException) {
            progress.background(clock());ledger.uncertain(permit);throw e
        } catch(_:Exception) {
            progress.background(clock());ledger.uncertain(permit);return false
        }
    }
    private suspend fun serialized(block:suspend ()->Boolean):Boolean {
        if(!command.tryLock()) return false
        try { return block() } finally { command.unlock() }
    }
}

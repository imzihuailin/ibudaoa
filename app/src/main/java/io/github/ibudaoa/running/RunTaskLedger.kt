package io.github.ibudaoa.running

import kotlinx.serialization.json.*
import java.util.UUID

internal enum class RunWrite { SELECT_ZONE, REGISTER, LOCATION, FINISH, GYROSCOPE }
enum class RunTaskPhase { PREPARED, ZONE_SELECTED, RUNNING, PAUSED, INTERRUPTED, WRITE_UNCERTAIN, AWAITING_VERDICT, VALID, INVALID }
data class RunTaskSummary(val phase:RunTaskPhase,val writePending:Boolean,val needsReconciliation:Boolean,
    val platformStarted:Boolean,val recordBound:Boolean,val recordVerified:Boolean)
internal interface RunTaskJournal {
    fun load():ByteArray?
    /** Must atomically and durably replace the previous encrypted checkpoint, or throw. */
    fun save(bytes:ByteArray)
    fun clear()
    fun archiveResolved(bytes: ByteArray)
}
internal class RunWritePermit internal constructor(internal val sequence:Long,internal val operation:RunWrite) {
    override fun toString()="RunWritePermit(private)"
}

/** Durable write intent precedes permission to send. No network, timer, route traversal or retry loop. */
internal class RunTaskLedger private constructor(private val journal:RunTaskJournal,private var checkpoint:JsonObject) {
    private var foreground=true
    private var storageFailed=false
    private var closed=false
    private var permit:RunWritePermit?=null
    private fun text(key:String)=checkpoint[key]?.jsonPrimitive?.content
    private fun number(key:String)=text(key)?.toLongOrNull()
    @get:Synchronized val summary:RunTaskSummary get() {
        val phase=RunTaskPhase.valueOf(text("phase")!!)
        return RunTaskSummary(if(storageFailed)RunTaskPhase.WRITE_UNCERTAIN else phase,text("pending")!=null,
            storageFailed || phase in setOf(RunTaskPhase.INTERRUPTED,RunTaskPhase.WRITE_UNCERTAIN),number("start")!=null,
            number("record")!=null,phase==RunTaskPhase.VALID || phase==RunTaskPhase.INVALID)
    }
    private fun save(changes:Map<String,JsonElement?>) {
        val next=checkpoint.toMutableMap()
        changes.forEach { (k,v) -> if(v==null) next.remove(k) else next[k]=v }
        val result=JsonObject(next)
        val bytes=result.toString().toByteArray()
        try { journal.save(bytes) } catch(e:Exception) { storageFailed=true;throw e } finally { bytes.fill(0) }
        checkpoint=result;storageFailed=false
    }
    @Synchronized fun begin(operation:RunWrite):RunWritePermit {
        check(!closed && !storageFailed && foreground && permit==null && text("pending")==null)
        val phase=summary.phase
        check(when(operation) {
            RunWrite.SELECT_ZONE -> phase==RunTaskPhase.PREPARED
            RunWrite.REGISTER -> phase==RunTaskPhase.ZONE_SELECTED
            RunWrite.LOCATION -> phase==RunTaskPhase.RUNNING || phase==RunTaskPhase.PAUSED && text("execution_mode")=="AUTOMATIC"
            RunWrite.FINISH -> phase in setOf(RunTaskPhase.RUNNING,RunTaskPhase.PAUSED)
            RunWrite.GYROSCOPE -> phase==RunTaskPhase.AWAITING_VERDICT && text("gyro_associated")!="true"
        })
        val seq=(number("sequence") ?: 0)+1
        check(seq>0)
        save(mapOf("pending" to JsonPrimitive(operation.name),"sequence" to JsonPrimitive(seq)))
        return RunWritePermit(seq,operation).also { permit=it }
    }
    private fun owns(value:RunWritePermit)=permit===value && number("sequence")==value.sequence && text("pending")==value.operation.name
    @Synchronized fun accept(value:RunWritePermit,response:ByteArray):Boolean {
        if(!owns(value)) return false
        val changes=mutableMapOf<String,JsonElement?>("pending" to null)
        try {
            require(response.size in 1..io.github.ibudaoa.diagnostics.DiagnosticCodec.MAX_BYTES)
            val responseText=response.toString(Charsets.UTF_8)
            io.github.ibudaoa.diagnostics.DiagnosticCodec.validateInput(responseText)
            val envelope=Json.parseToJsonElement(responseText) as JsonObject
            require(envelope["status"]?.jsonPrimitive?.intOrNull==1)
            val next=when(value.operation) {
                RunWrite.SELECT_ZONE -> RunTaskPhase.ZONE_SELECTED
                RunWrite.REGISTER -> {
                    val registered=RegisteredRun.decode(response)
                    changes["start"]=JsonPrimitive(registered.startTime)
                    changes["proof"]=JsonPrimitive(registered.proofForFinish())
                    changes["interval"]=JsonPrimitive(registered.locationIntervalSeconds)
                    RunTaskPhase.RUNNING
                }
                RunWrite.GYROSCOPE -> { changes["gyro_associated"]=JsonPrimitive(true);RunTaskPhase.AWAITING_VERDICT }
                RunWrite.LOCATION -> RunTaskPhase.RUNNING // Acknowledgement does not mark any checkpoint completed.
                RunWrite.FINISH -> {
                    val data=io.github.ibudaoa.capture.BeforeRunDecoder.decodeSuccessfulData(response)
                    fun long(key:String)=(data[key] as? JsonPrimitive)?.longOrNull
                    val record=long("record_id")
                    check(record!=null && record>0 && long("uid")==number("account") &&
                        long("game_id")==number("zone") && long("start_time")==number("start"))
                    changes["record"]=JsonPrimitive(record)
                    RunTaskPhase.AWAITING_VERDICT
                }
            }
            changes["phase"]=JsonPrimitive(if(!foreground && next !in setOf(RunTaskPhase.AWAITING_VERDICT))
                RunTaskPhase.INTERRUPTED.name else next.name)
        } catch(_:Exception) {
            // A malformed/failed response is not proof of absence of a side effect.
            uncertain(value)
            return false
        }
        // Failed persistence keeps the old pending intent and permit, never grants another write.
        save(changes);permit=null
        return true
    }
    @Synchronized fun uncertain(value:RunWritePermit) {
        if(!owns(value)) return
        save(mapOf("phase" to JsonPrimitive(RunTaskPhase.WRITE_UNCERTAIN.name)))
        permit=null
    }
    @Synchronized fun pause() {
        check(!closed && !storageFailed && foreground && permit==null && text("pending")==null && summary.phase==RunTaskPhase.RUNNING)
        save(mapOf("phase" to JsonPrimitive(RunTaskPhase.PAUSED.name)))
    }
    @Synchronized fun resume() {
        check(!closed && !storageFailed && foreground && permit==null && text("pending")==null && summary.phase==RunTaskPhase.PAUSED)
        save(mapOf("phase" to JsonPrimitive(RunTaskPhase.RUNNING.name)))
    }
    @Synchronized fun background() {
        foreground=false
        if(summary.phase in setOf(RunTaskPhase.VALID,RunTaskPhase.INVALID,RunTaskPhase.AWAITING_VERDICT,RunTaskPhase.WRITE_UNCERTAIN)) return
        save(mapOf("phase" to JsonPrimitive(RunTaskPhase.INTERRUPTED.name)))
    }
    @Synchronized fun foreground() { foreground=true } // Never resumes or replays a write.
    @Synchronized fun verifyRecord(response:ByteArray):RunRecordVerification {
        check(foreground && summary.phase in setOf(RunTaskPhase.AWAITING_VERDICT,RunTaskPhase.WRITE_UNCERTAIN,RunTaskPhase.INTERRUPTED,RunTaskPhase.VALID,RunTaskPhase.INVALID) && number("record")!=null)
        if(text("gyro_required")=="true" && text("gyro_associated")!="true" && text("pending")!="GYROSCOPE")return RunRecordVerification(RunRecordVerdict.PENDING)
        val expected=ExpectedRunRecord(number("record")!!,number("account")!!,number("zone")!!,number("start")!!)
        val inspected=RunRecordVerifier.inspect(response,expected)
        val result=inspected.summary
        save(mapOf(
            "identity_checks" to result.identityChecks.takeIf { it.isNotEmpty() }?.let { checks -> JsonObject(checks.mapValues { JsonPrimitive(it.value.name) }) },
            "last_verdict" to JsonPrimitive(result.verdict.name)))
        if(result.identityMatched) {
            val changes=mutableMapOf<String,JsonElement?>("failure_reason" to inspected.failureReason?.let(::JsonPrimitive),
                "server_points" to result.serverPointCount?.let(::JsonPrimitive),"server_status" to result.serverStatus?.let(::JsonPrimitive))
            if(result.verdict in setOf(RunRecordVerdict.VALID,RunRecordVerdict.INVALID))
                changes["phase"]=JsonPrimitive(if(result.platformValid)RunTaskPhase.VALID.name else RunTaskPhase.INVALID.name)
            if(result.verdict in setOf(RunRecordVerdict.VALID,RunRecordVerdict.INVALID))changes["pending"]=null
            save(changes)
        }
        return result
    }
    @Synchronized fun markFinishStage(stage:RunFinishStage) {
        check(!closed && !storageFailed && text("pending")==RunWrite.FINISH.name)
        save(mapOf("finish_stage" to JsonPrimitive(stage.name)))
    }
    @Synchronized fun recordCompletionChecks(problems:Set<RunCompletionProblem>,acknowledged:Int) {
        check(!closed && foreground && !storageFailed && acknowledged in 0..24)
        save(mapOf("completion_checks" to JsonArray(problems.sortedBy { it.name }.map { JsonPrimitive(it.name) }),
            "acknowledged_points" to JsonPrimitive(acknowledged)))
    }
    @Synchronized fun completionChecks():Set<RunCompletionProblem> = (checkpoint["completion_checks"] as? JsonArray)
        ?.mapNotNull { value -> RunCompletionProblem.entries.firstOrNull { it.name==(value as? JsonPrimitive)?.content } }?.toSet().orEmpty()
    @Synchronized fun acknowledgedPoints():Int = number("acknowledged_points")?.takeIf { it in 0..24 }?.toInt() ?: 0
    @Synchronized fun recordMaterialFailure(problem:RunMaterialProblem) {
        check(!closed && foreground && !storageFailed)
        save(mapOf("material_problem" to JsonPrimitive(problem.name)))
    }
    @Synchronized fun materialProblem():RunMaterialProblem? = RunMaterialProblem.entries.firstOrNull { it.name==text("material_problem") }
    @Synchronized fun finishStage():RunFinishStage? = text("finish_stage")?.let { RunFinishStage.valueOf(it) }
    @Synchronized fun canArchiveAfterManualNoRecord():Boolean = !closed && !storageFailed && foreground &&
        summary.phase==RunTaskPhase.WRITE_UNCERTAIN && text("pending")==RunWrite.FINISH.name && number("start")!=null && number("record")==null
    @Synchronized fun pendingWrite():RunWrite? = text("pending")?.let { RunWrite.valueOf(it) }
    @Synchronized fun serverPointCount():Int? = number("server_points")?.toInt()
    @Synchronized fun identityChecks(): Map<String, RecordFieldCheck> =
        (checkpoint["identity_checks"] as? JsonObject)?.mapNotNull { (key, value) ->
            if (key !in setOf("id", "uid", "game_id", "start_time", "type")) null
            else runCatching { key to RecordFieldCheck.valueOf(value.jsonPrimitive.content) }.getOrNull()
        }?.toMap() ?: emptyMap()
    @Synchronized fun lastVerdict(): String? = text("last_verdict")?.takeIf { value -> RunRecordVerdict.entries.any { it.name == value } }
    @Synchronized fun serverRecordStatus():Int? = number("server_status")?.toInt()
    @Synchronized fun failureReasonForDisplay():String? = text("failure_reason")
    @Synchronized fun requestContext():RunRequestContext {
        check(!closed)
        return RunRequestContext(number("account")!!,number("school")!!,number("term")!!,number("zone")!!,
            (checkpoint.getValue("points") as JsonArray).map { it.jsonPrimitive.content },number("start"),text("proof"))
    }
    @Synchronized fun locationIntervalSeconds():Int = number("interval")?.toInt() ?: error("Run not registered")
    @Synchronized fun expectedRecord():ExpectedRunRecord {
        check(!closed && foreground && number("record")!=null && summary.phase in setOf(RunTaskPhase.AWAITING_VERDICT,RunTaskPhase.WRITE_UNCERTAIN,RunTaskPhase.INTERRUPTED,RunTaskPhase.VALID,RunTaskPhase.INVALID))
        return ExpectedRunRecord(number("record")!!,number("account")!!,number("zone")!!,number("start")!!)
    }
    @Synchronized fun canAbandonUnsent():Boolean = !closed && number("sequence")==0L && text("pending")==null && number("start")==null && number("record")==null
    @Synchronized fun abandonUnsent() {
        check(canAbandonUnsent())
        journal.clear();closed=true
    }
    @Synchronized fun archiveResolved() {
        check(!closed && !storageFailed && foreground && text("pending")==null &&
            summary.phase in setOf(RunTaskPhase.VALID,RunTaskPhase.INVALID))
        val minimal = JsonObject(checkpoint.filterKeys { it in setOf("schema", "task", "phase", "execution_mode", "account", "school", "term", "zone", "points", "start", "record", "finish_stage", "server_status", "server_points", "failure_reason", "identity_checks", "last_verdict") }).toString().toByteArray()
        try { journal.archiveResolved(minimal) } finally { minimal.fill(0) }
    }
    @Synchronized fun releaseResolved() {
        archiveResolved()
        journal.clear();closed=true
    }
    override fun toString()="RunTaskLedger(private)"
    companion object {
        fun create(journal:RunTaskJournal,account:Long,school:Long,term:Long,zone:Long,points:List<String>,generated:Boolean=false,gyroRequired:Boolean=false):RunTaskLedger {
            check(journal.load()?.also { it.fill(0) }==null) { "Unresolved run journal already exists" }
            require(listOf(account,school,term,zone).all { it>0 } && points.size in 2..24 && points.distinct().size==points.size &&
                points.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) })
            val initial=buildJsonObject {
                put("schema",1);put("task",UUID.randomUUID().toString());put("phase",RunTaskPhase.PREPARED.name)
                put("execution_mode",if(generated)"AUTOMATIC" else "DEVICE_GPS");put("gyro_required",gyroRequired)
                put("account",account);put("school",school);put("term",term);put("zone",zone)
                put("points",JsonArray(points.map(::JsonPrimitive)));put("sequence",0)
            }
            val ledger=RunTaskLedger(journal,initial)
            ledger.save(emptyMap())
            return ledger
        }
        fun restore(journal:RunTaskJournal,account:Long):RunTaskLedger? {
            val bytes=journal.load() ?: return null
            val checkpoint=try {
                require(bytes.size in 1..16_384)
                val o=Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
                require(account>0 && o["account"]?.jsonPrimitive?.longOrNull==account) { "Run checkpoint belongs to a different account" }
                require(o["schema"]?.jsonPrimitive?.intOrNull==1)
                require(o["task"]?.jsonPrimitive?.content?.let { UUID.fromString(it).toString()==it }==true)
                val phase=RunTaskPhase.valueOf(o.getValue("phase").jsonPrimitive.content)
                require(listOf("account","school","term","zone").all { (o[it] as? JsonPrimitive)?.longOrNull?.let { n -> n>0 }==true })
                val points=(o["points"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: error("Missing points")
                require(points.size in 2..24 && points.distinct().size==points.size && points.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) })
                require(o["sequence"]?.jsonPrimitive?.longOrNull?.let { it>=0 }==true)
                o["pending"]?.let { RunWrite.valueOf(it.jsonPrimitive.content) }
                if(phase in setOf(RunTaskPhase.RUNNING,RunTaskPhase.PAUSED,RunTaskPhase.AWAITING_VERDICT,RunTaskPhase.VALID,RunTaskPhase.INVALID) || o.containsKey("start")) {
                    require(o["start"]?.jsonPrimitive?.longOrNull?.let { it>0 }==true)
                    require(o["proof"]?.jsonPrimitive?.content?.matches(Regex("[a-fA-F0-9]{32}"))==true)
                    require(o["interval"]?.jsonPrimitive?.intOrNull in 1..3600)
                }
                if(phase in setOf(RunTaskPhase.AWAITING_VERDICT,RunTaskPhase.VALID,RunTaskPhase.INVALID))
                    require(o["record"]?.jsonPrimitive?.longOrNull?.let { it>0 }==true)
                o
            } finally { bytes.fill(0) }
            val ledger=RunTaskLedger(journal,checkpoint)
            ledger.foreground=false
            if(ledger.text("pending")!=null) ledger.save(mapOf("phase" to JsonPrimitive(RunTaskPhase.WRITE_UNCERTAIN.name)))
            else if(ledger.summary.phase !in setOf(RunTaskPhase.AWAITING_VERDICT,RunTaskPhase.VALID,RunTaskPhase.INVALID))
                ledger.save(mapOf("phase" to JsonPrimitive(RunTaskPhase.INTERRUPTED.name)))
            return ledger
        }
    }
}

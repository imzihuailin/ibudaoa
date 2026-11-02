package io.github.ibudaoa.capture

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.diagnostics.StageVerification
import io.github.ibudaoa.running.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId

/** Generated-route execution, separate from the device-GPS gateway and physical sensor producer. */
internal class AutomaticRunGateway(private val session:ForegroundAccountSession,private val config:DiagnosticConfig,
    private val requirements:RunRuntimeRequirements,private val now:()->Instant={Instant.now()},
    private val connection:()->AccountReadConnection={RunApiHttpsTransport()},private val uploader:()->RunObjectUpload={RunObjectUpload()}) {
    init {
        val gyro=(config.config["gyroscope_type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        require(gyro in setOf("0","1") && (gyro=="1")==requirements.gyroscopeRequired)
    }
    private var motionName:String?=null
    private var motionOwner:RunRequestContext?=null
    fun preparationProblem():String? = requirements.blockingProblem?.let { "REQUIREMENT_${it.name}" }
        ?: if(config.stageVerification !in setOf(StageVerification.SKIPPED_EMPTY,StageVerification.DISABLED))"REQUIREMENT_FACE" else null
    fun registrationTimeAccepted(start:Long):Boolean = requirements.timeCheck!=RuntimeSwitch.ENABLED ||
        runCatching { Math.subtractExact(start,now().epochSecond) in -600L..600L }.getOrDefault(false)
    private fun call(endpoint:RunEndpoint,context:RunRequestContext,fields:Map<String,String>)=
        session.runRequest(connection(),endpoint,context,fields,now().epochSecond,100_000+SecureRandom().nextInt(900_000))
    fun completionProblems(context:RunRequestContext,snapshot:AutomaticRunSnapshot)=snapshot.ruleProblems(config,context.registeredStart ?: error("Run not registered"))
    fun validateCompletion(context:RunRequestContext,snapshot:AutomaticRunSnapshot) {
        snapshot.validate(context)
        if(listOf("school_id" to context.school,"term_id" to context.term,"run_zone_id" to context.zone).any { (key,id) ->
                (config.config[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()!=id
            })throw RunMaterialException(RunMaterialProblem.IDENTITY)
    }
    suspend fun write(kind:RunWrite,context:RunRequestContext,timeline:AutomaticRouteRun,onStage:(RunFinishStage)->Unit={},
        location:AutomaticRunSample?=null):ByteArray=withContext(Dispatchers.IO) {
        check(requirements.automaticSupported && config.stageVerification in setOf(StageVerification.SKIPPED_EMPTY,StageVerification.DISABLED))
        when(kind) {
            RunWrite.SELECT_ZONE -> call(RunEndpoint.ZONE,context,mapOf("run_zone_id" to context.zone.toString()))
            RunWrite.REGISTER -> call(RunEndpoint.REGISTER,context,mapOf("game_id" to context.zone.toString(),"point_ids" to context.points.joinToString(",")))
            RunWrite.LOCATION -> {
                val point=location ?: timeline.current() ?: error("Automatic position not ready")
                call(RunEndpoint.LOCATION,context,mapOf("game_id" to context.zone.toString(),"point_ids" to context.points.joinToString(","),
                    "latitude" to point.coordinate.latitude.toString(),"longitude" to point.coordinate.longitude.toString()))
            }
            RunWrite.FINISH -> {
                onStage(RunFinishStage.VALIDATION)
                val snapshot=timeline.completion()
                validateCompletion(context,snapshot)
                onStage(RunFinishStage.STORAGE_REQUEST)
                val sts=call(RunEndpoint.STORAGE,context,emptyMap())
                val grant=try { onStage(RunFinishStage.STORAGE_DECODE);RunStorageGrant.decode(sts,now()) } finally { sts.fill(0) }
                val client=uploader()
                val trackName=client.objectName("run_record",now())
                val gyroName=client.objectName("run_gyroscope",now())
                onStage(RunFinishStage.FILE_BUILD)
                AutomaticRunFiles.build(context,config,snapshot,trackName).use { data ->
                    session.runStorageIo(context.account,client) {
                        onStage(RunFinishStage.TRACK_UPLOAD)
                        client.putFile(grant,trackName,data.track,now())
                        if(requirements.gyroscopeRequired==true) { onStage(RunFinishStage.MOTION_UPLOAD);client.putFile(grant,gyroName,data.motion,now()) }
                    }
                    motionName=if(requirements.gyroscopeRequired==true)gyroName else null;motionOwner=context
                    onStage(RunFinishStage.FINISH_REQUEST)
                    call(RunEndpoint.FINISH,context,data.fields).also { onStage(RunFinishStage.FINISH_RESPONSE) }
                }
            }
            RunWrite.GYROSCOPE -> error("Record identity required for association")
        }
    }
    suspend fun associate(context:RunRequestContext,record:ExpectedRunRecord):ByteArray=withContext(Dispatchers.IO) {
        val owner=motionOwner ?: error("Automatic motion upload missing")
        check(record.accountId==context.account && record.zoneId==context.zone && record.startTime==context.registeredStart &&
            owner.account==context.account && owner.registeredStart==context.registeredStart && owner.registrationProof==context.registrationProof)
        call(RunEndpoint.GYROSCOPE,context,mapOf("record_id" to record.recordId.toString(),"gyroscope_file" to (motionName ?: error("Automatic motion file missing"))))
    }
    suspend fun record(context:RunRequestContext,record:ExpectedRunRecord):ByteArray=withContext(Dispatchers.IO) {
        check(record.accountId==context.account && record.zoneId==context.zone && record.startTime==context.registeredStart)
        call(RunEndpoint.RECORD,context,mapOf("record_id" to record.recordId.toString(),"record_uid" to context.account.toString(),
            "year_num" to Instant.ofEpochSecond(record.startTime).atZone(ZoneId.of("Asia/Shanghai")).year.toString()))
    }
}

/** Read an already bound record without requiring new-run configuration. */
internal class RunRecordReader(private val session: ForegroundAccountSession,
    private val now: () -> Instant = { Instant.now() },
    private val connection: () -> AccountReadConnection = { RunApiHttpsTransport() }) {
    suspend fun record(context: RunRequestContext, record: ExpectedRunRecord): ByteArray = withContext(Dispatchers.IO) {
        check(record.accountId == context.account && record.zoneId == context.zone && record.startTime == context.registeredStart)
        val transport=connection()
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            // Coroutine cancellation must close the blocking HTTPS read, including at the verdict deadline.
            continuation.invokeOnCancellation { runCatching { transport.close() } }
            try {
                val body=session.runRequest(transport, RunEndpoint.RECORD, context, mapOf(
                    "record_id" to record.recordId.toString(), "record_uid" to context.account.toString(),
                    "year_num" to Instant.ofEpochSecond(record.startTime).atZone(ZoneId.of("Asia/Shanghai")).year.toString()),
                    now().epochSecond, 100_000 + SecureRandom().nextInt(900_000))
                continuation.resume(body,onCancellation={ _,value,_ -> value.fill(0) })
            } catch(e:Exception) { continuation.resumeWith(Result.failure(e)) }
        }
    }
}

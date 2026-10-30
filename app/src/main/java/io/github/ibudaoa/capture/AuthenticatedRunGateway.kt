package io.github.ibudaoa.capture

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.diagnostics.StageVerification
import io.github.ibudaoa.maps.DisplayFix
import io.github.ibudaoa.running.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.security.SecureRandom

/** Actual HTTPS adapter. Production start remains gated until sensor/check readiness and UI lifecycle are wired. */
internal class AuthenticatedRunGateway(private val session:ForegroundAccountSession,private val config:DiagnosticConfig,
    private val finishReady:()->Boolean,private val finishMeasurements:()->RunFinishMeasurements,
    private val now:()->Instant={Instant.now()},private val nonce:()->Int={100_000+SecureRandom().nextInt(900_000)},
    private val connection:()->AccountReadConnection={RunApiHttpsTransport()},
    private val uploader:()->RunObjectUpload={RunObjectUpload()},private val restoredContext:RunRequestContext?=null):RunExecutionGateway {
    private var lastContext:RunRequestContext?=null
    private fun request(endpoint:RunEndpoint,context:RunRequestContext,fields:Map<String,String>):ByteArray =
        session.runRequest(connection(),endpoint,context,fields,now().epochSecond,nonce())
    override suspend fun write(operation:RunWrite,context:RunRequestContext,fix:DisplayFix?,progress:RunProgressSummary,
        trace:List<MeasuredRunFix>,checkpoints:List<ObservedRunCheckpoint>):ByteArray = withContext(Dispatchers.IO) {
        require(config.stageVerification in setOf(StageVerification.SKIPPED_EMPTY,StageVerification.DISABLED))
        if(operation in setOf(RunWrite.SELECT_ZONE,RunWrite.REGISTER,RunWrite.FINISH)) {
            check(finishReady()) { "Run finish adapter not ready" }
            check((config.config["gyroscope_type"] as? kotlinx.serialization.json.JsonPrimitive)?.content=="0") {
                "Gyroscope record association adapter not ready"
            }
        }
        when(operation) {
            RunWrite.GYROSCOPE -> error("Device sensor association not implemented")
            RunWrite.SELECT_ZONE -> request(RunEndpoint.ZONE,context,mapOf("run_zone_id" to context.zone.toString()))
            RunWrite.REGISTER -> request(RunEndpoint.REGISTER,context,mapOf("game_id" to context.zone.toString(),"point_ids" to context.points.joinToString(",")))
            RunWrite.LOCATION -> {
                require(fix!=null && trace.any { it.fix==fix })
                request(RunEndpoint.LOCATION,context,mapOf("game_id" to context.zone.toString(),"point_ids" to context.points.joinToString(","),
                    "latitude" to fix.coordinate.latitude.toString(),"longitude" to fix.coordinate.longitude.toString()))
            }
            RunWrite.FINISH -> {
                check(finishReady())
                val measurements=finishMeasurements()
                RunTraceArchive.build(context,config,progress,trace).use { archive ->
                    val response=request(RunEndpoint.STORAGE,context,emptyMap())
                    val grant=try { RunStorageGrant.decode(response,now()) } finally { response.fill(0) }
                    val client=uploader()
                    val receipt=session.runStorageIo(context.account,client) { client.upload(grant,archive,now()) }
                    val fields=RunFinishFields.build(context,receipt,checkpoints,measurements)
                    request(RunEndpoint.FINISH,context,fields)
                }
            }
        }.also { lastContext=context }
    }
    override suspend fun record(expected:ExpectedRunRecord):ByteArray=withContext(Dispatchers.IO) {
        val context=lastContext ?: restoredContext ?: error("Run record context missing")
        require(expected.accountId==context.account && expected.zoneId==context.zone && expected.startTime==context.registeredStart)
        request(RunEndpoint.RECORD,context,mapOf("record_id" to expected.recordId.toString(),"record_uid" to expected.accountId.toString(),
            "year_num" to Instant.ofEpochSecond(expected.startTime).atZone(ZoneId.of("Asia/Shanghai")).year.toString()))
    }
}

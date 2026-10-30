package io.github.ibudaoa.running

import io.github.ibudaoa.capture.BeforeRunDecoder
import kotlinx.serialization.json.*

/** An exact record and run context, never a 'latest record' lookup. Internal, excluded from diagnostics. */
internal class ExpectedRunRecord(val recordId:Long,val accountId:Long,val zoneId:Long,val startTime:Long) {
    init { require(recordId>0 && accountId>0 && zoneId>0 && startTime>0) }
    override fun toString()="ExpectedRunRecord(private)"
}
enum class RecordFieldCheck { MATCH, MISSING, INVALID, MISMATCH }
enum class RunRecordVerdict { VALID, INVALID, PENDING, UNKNOWN, MISMATCH, QUERY_FAILED }
data class RunRecordVerification(val verdict:RunRecordVerdict,val identityMatched:Boolean=false,
    val hasFailureReason:Boolean=false,val serverPointCount:Int?=null,val serverStatus:Int?=null,
    val identityChecks:Map<String,RecordFieldCheck> = emptyMap()) {
    val platformValid:Boolean get()=identityMatched && verdict==RunRecordVerdict.VALID
}

internal class InspectedRunRecord(val summary:RunRecordVerification,val failureReason:String?) {
    override fun toString()="InspectedRunRecord(private)"
}

/** Only exact response binding plus explicit server status can establish platform validity. */
internal object RunRecordVerifier {
    const val DETAIL_PATH="/v3/api.php/Run/recordDetailV270"
    fun decode(bytes:ByteArray,expected:ExpectedRunRecord):RunRecordVerification = inspect(bytes,expected).summary
    fun inspect(bytes:ByteArray,expected:ExpectedRunRecord):InspectedRunRecord {
        return try {
            val data=BeforeRunDecoder.decodeSuccessfulData(bytes)
            fun number(key:String)=(data[key] as? JsonPrimitive)?.longOrNull
            val checks = mapOf("id" to expected.recordId, "uid" to expected.accountId, "game_id" to expected.zoneId,
                "start_time" to expected.startTime, "type" to 1L).mapValues { (key, expectedValue) ->
                val value = data[key]
                when {
                    value == null -> RecordFieldCheck.MISSING
                    (value as? JsonPrimitive)?.longOrNull == null -> RecordFieldCheck.INVALID
                    number(key) != expectedValue -> RecordFieldCheck.MISMATCH
                    else -> RecordFieldCheck.MATCH
                }
            }
            if (checks.any { (key, check) -> check != RecordFieldCheck.MATCH && !(key == "id" && check == RecordFieldCheck.MISSING) })
                return InspectedRunRecord(RunRecordVerification(RunRecordVerdict.MISMATCH, identityChecks=checks),null)
            val reason=(data["record_failed_reason"] as? JsonPrimitive)?.takeIf { it.isString }
                ?.content?.isNotBlank()==true
            val status=number("record_status")
            val verdict=when(status) {
                1L -> if(reason)RunRecordVerdict.UNKNOWN else RunRecordVerdict.VALID
                0L -> if(reason)RunRecordVerdict.INVALID else RunRecordVerdict.UNKNOWN
                2L -> RunRecordVerdict.INVALID // Official local invalid record and unlinked-grade representation.
                10L -> RunRecordVerdict.PENDING // Official adapter marks this separately as an upload anomaly.
                else -> RunRecordVerdict.UNKNOWN
            }
            val text=(data["record_failed_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() }?.let { raw ->
                    if(raw.length>500 || Regex("https?://|[0-9]{6,}|[a-fA-F0-9]{24,}|token|cookie|authorization",RegexOption.IGNORE_CASE).containsMatchIn(raw))
                        "平台原因包含敏感内容，已隐藏"
                    else raw.map { if(it.isISOControl()) ' ' else it }.joinToString("").trim()
                }
            InspectedRunRecord(RunRecordVerification(verdict,true,reason,
                number("log_num")?.takeIf { it in 0..24 }?.toInt(),status?.takeIf { it in -1..1000 }?.toInt(), checks),text)
        } catch(_:Exception) { InspectedRunRecord(RunRecordVerification(RunRecordVerdict.QUERY_FAILED),null) }
    }
}

/** Server registration acknowledgement, not a finished record ID or proof that the run is valid. */
internal class RegisteredRun private constructor(val startTime:Long,val locationIntervalSeconds:Int,
    private val registrationProof:String) {
    fun proofForFinish()=registrationProof
    override fun toString()="RegisteredRun(private)"
    companion object {
        fun decode(bytes:ByteArray):RegisteredRun {
            val data=BeforeRunDecoder.decodeSuccessfulData(bytes)
            val time=(data["timestamp"] as? JsonPrimitive)?.longOrNull
            val interval=(data["location_septal"] as? JsonPrimitive)?.intOrNull
            val proof=(data["str"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(time!=null && time>0 && interval!=null && interval in 1..3600 &&
                proof!=null && proof.matches(Regex("[a-fA-F0-9]{32}"))) { "Invalid run registration" }
            return RegisteredRun(time,interval,proof)
        }
    }
}

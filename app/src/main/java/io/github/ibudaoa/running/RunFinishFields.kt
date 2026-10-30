package io.github.ibudaoa.running

import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest

/** Must be supplied by the actual sensor/check producer. No fallback step count or fabricated samples. */
internal class RunFinishMeasurements(val stepCount:Int,perMinuteSteps:List<Int>,runChecks:JsonArray) {
    val perMinuteSteps=perMinuteSteps.toList()
    val runChecks=JsonArray(runChecks.toList())
    init {
        require(stepCount in 0..100_000 && perMinuteSteps.size in 1..1440 &&
            perMinuteSteps.all { it in 0..1000 } && perMinuteSteps.sumOf { it.toLong() }==stepCount.toLong())
        require(runChecks.size<=100 && runChecks.toString().length<=32_768)
    }
    override fun toString()="RunFinishMeasurements(private)"
}
internal object RunFinishFields {
    private val recordSalts=listOf("aB3dE9fG2hJkL5mN","PqR7sT8uV9wXyZ1","A2bC3dE4fG5hI6j","K7lM8nO9pQ0rS1t","U2vW3xY4zA5bC6d",
        "E7fG8hI9jK0lM1n","O2pQ3rS4tU5vW6x","Y7zA8bC9dE0fG1h","I2jK3lM4nO5pQ6r","S7tU8vW9xY0zA1b")
    internal fun recordSignature(context:RunRequestContext):String {
        require(context.account in 1..Int.MAX_VALUE && context.registrationProof!=null)
        val input=Math.addExact(context.school,context.account).toString()+context.registrationProof+recordSalts[(context.account%10).toInt()]
        return MessageDigest.getInstance("MD5").digest(input.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun build(context:RunRequestContext,uploaded:UploadedRunTrace,checkpoints:List<ObservedRunCheckpoint>,measurements:RunFinishMeasurements):Map<String,String> {
        val owner=uploaded.context
        require(context.account==owner.account && context.school==owner.school && context.term==owner.term && context.zone==owner.zone &&
            context.points==owner.points && context.registeredStart==owner.registeredStart && context.registrationProof==owner.registrationProof)
        require(context.account in 1..Int.MAX_VALUE && context.registeredStart!=null &&
            context.registrationProof?.matches(Regex("[a-fA-F0-9]{32}"))==true)
        require(uploaded.elapsedSeconds in 1..86_400)
        val minutes=((uploaded.elapsedSeconds+59)/60).toInt()
        require(measurements.perMinuteSteps.size in minutes..minutes+(if(uploaded.elapsedSeconds%60==0L)1 else 0))
        require(checkpoints.map { it.id }.distinct().size==checkpoints.size && checkpoints.all { it.id in context.points && uploaded.contains(it) })
        val observed=checkpoints.associateBy { it.id }
        val logData=buildJsonArray {
            context.points.forEach { id ->
                require(id.toLongOrNull()?.let { it>0 }==true)
                val point=observed[id]
                add(buildJsonObject {
                    put("point_id",id.toLong())
                    put("time",point?.fix?.sdkTimeMillis?.div(1000) ?: 0L)
                    put("latitude",point?.fix?.coordinate?.latitude?.toFloat()?.toDouble() ?: 0.0)
                    put("longtitude",point?.fix?.coordinate?.longitude?.toFloat()?.toDouble() ?: 0.0)
                    put("distance",point?.cumulativeMeters?.div(1000)?.toFloat()?.toDouble() ?: 0.0)
                })
            }
        }
        // Official P4 appends the numeric school+uid sum, registration proof and public uid%10 salt.
        val recordStr=recordSignature(context)
        return mapOf(
            "term_id" to context.term.toString(),"game_id" to context.zone.toString(),"log_data" to logData.toString(),
            "file_img" to "1", // Official P4 fallback when no optional screenshot exists.
            "used_time" to uploaded.elapsedSeconds.toString(),"start_time" to context.registeredStart.toString(),
            "end_time" to Math.addExact(context.registeredStart,uploaded.elapsedSeconds).toString(),
            "distance" to BigDecimal.valueOf(uploaded.distanceMeters/1000).setScale(2,RoundingMode.HALF_UP).toPlainString(),
            "step_num" to measurements.stepCount.toString(),"record_file" to uploaded.objectName,"record_img" to "",
            "step_info" to buildJsonObject { put("interval",60);put("list",JsonArray(measurements.perMinuteSteps.map(::JsonPrimitive))) }.toString(),
            "record_str" to recordStr,"pass_total" to uploaded.samples.toString(),"pass_effective_num" to uploaded.insideSamples.toString(),
            "is_running_area_valid" to if(uploaded.allInsideArea)"1" else "0","run_check_res" to measurements.runChecks.toString(),
            "run_face_verify_id" to "1231231231231", // Official empty-face-ID sentinel; never claims a face verification.
            "is_timeout_zfb" to "0")
    }
}

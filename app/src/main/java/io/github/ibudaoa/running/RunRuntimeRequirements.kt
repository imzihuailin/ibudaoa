package io.github.ibudaoa.running

import kotlinx.serialization.json.*

enum class RuntimeCheckShape { MISSING, NULL, EMPTY_ARRAY, NONEMPTY_ARRAY, EMPTY_STRING, ENCODED_EMPTY_ARRAY, ENCODED_NONEMPTY_ARRAY, OTHER }
enum class RuntimeSwitch { DISABLED, ENABLED, MISSING, NULL, INVALID }
enum class RunRequirementProblem(val label:String) {
    BEHAVIOR("当前行为检查规则尚未适配"), ENVIRONMENT("当前环境检查规则尚未适配"),
    DEVICE("当前设备检查要求尚未适配或配置不完整"), GEE_DEVICE("当前设备风控要求尚未适配或配置不完整"),
    TIME("当前时间校验配置不完整"), MOTION("当前运动文件要求未知"), VERIFICATION("当前第三方验证要求尚未适配或配置不完整")
}
data class RunRuntimeRequirements(val runChecks:RuntimeCheckShape=RuntimeCheckShape.MISSING,
    val androidChecks:RuntimeCheckShape=RuntimeCheckShape.MISSING,val gyroscopeRequired:Boolean?=null,
    val verificationSupported:Boolean=false,
    val deviceCheck:RuntimeSwitch=RuntimeSwitch.MISSING,val geeDeviceCheck:RuntimeSwitch=RuntimeSwitch.MISSING,
    val timeCheck:RuntimeSwitch=RuntimeSwitch.MISSING,
    val verificationChecks:Map<String,RuntimeSwitch> = emptyMap()) {
    val blockingProblem:RunRequirementProblem? get()=when {
        runChecks !in setOf(RuntimeCheckShape.EMPTY_ARRAY,RuntimeCheckShape.ENCODED_EMPTY_ARRAY,RuntimeCheckShape.EMPTY_STRING) -> RunRequirementProblem.BEHAVIOR
        androidChecks!=RuntimeCheckShape.EMPTY_ARRAY -> RunRequirementProblem.ENVIRONMENT
        deviceCheck!=RuntimeSwitch.DISABLED -> RunRequirementProblem.DEVICE
        geeDeviceCheck!=RuntimeSwitch.DISABLED -> RunRequirementProblem.GEE_DEVICE
        timeCheck !in setOf(RuntimeSwitch.DISABLED,RuntimeSwitch.ENABLED) -> RunRequirementProblem.TIME
        gyroscopeRequired==null -> RunRequirementProblem.MOTION
        !verificationSupported -> RunRequirementProblem.VERIFICATION
        else -> null
    }
    val automaticSupported:Boolean get()=blockingProblem==null
    val supported:Boolean get()=automaticSupported && runChecks!=RuntimeCheckShape.EMPTY_STRING
    companion object {
        fun inspect(data:JsonObject):RunRuntimeRequirements = runCatching {
            fun shape(value:JsonElement?):RuntimeCheckShape=when(value) {
                null -> RuntimeCheckShape.MISSING
                JsonNull -> RuntimeCheckShape.NULL
                is JsonArray -> if(value.isEmpty())RuntimeCheckShape.EMPTY_ARRAY else RuntimeCheckShape.NONEMPTY_ARRAY
                is JsonPrimitive -> if(value.isString) {
                    if(value.content.isEmpty())RuntimeCheckShape.EMPTY_STRING
                    else {
                        val array=if(value.content.length<=32768)runCatching { Json.parseToJsonElement(value.content) as? JsonArray }.getOrNull() else null
                        when { array==null -> RuntimeCheckShape.OTHER;array.isEmpty() -> RuntimeCheckShape.ENCODED_EMPTY_ARRAY;else -> RuntimeCheckShape.ENCODED_NONEMPTY_ARRAY }
                    }
                } else RuntimeCheckShape.OTHER
                else -> RuntimeCheckShape.OTHER
            }
            fun integer(k:String)=(data[k] as? JsonPrimitive)?.intOrNull
            fun switch(k:String)=when(data[k]) {
                null -> RuntimeSwitch.MISSING
                JsonNull -> RuntimeSwitch.NULL
                else -> when(integer(k)) { 0 -> RuntimeSwitch.DISABLED;1 -> RuntimeSwitch.ENABLED;else -> RuntimeSwitch.INVALID }
            }
            val robot=when(val value=data["robot_module"]) {
                null -> RuntimeSwitch.MISSING
                JsonNull -> RuntimeSwitch.NULL
                else -> if(value is JsonPrimitive && !value.isString)when(value.booleanOrNull) {
                    false -> RuntimeSwitch.DISABLED;true -> RuntimeSwitch.ENABLED;null -> RuntimeSwitch.INVALID
                } else RuntimeSwitch.INVALID
            }
            val verification=listOf("is_check_geetest","is_check_shumeng","is_check_deepkonw").associateWith(::switch)+("robot_module" to robot)
            RunRuntimeRequirements(shape(data["run_check"]),shape(data["android_cheats"]),when(integer("gyroscope_type")) { 0 -> false;1 -> true;else -> null },
                verification.values.all { it==RuntimeSwitch.DISABLED },
                switch("is_check_device"),switch("is_check_geedevice"),switch("is_check_time"),verification)
        }.getOrElse { RunRuntimeRequirements() }
    }
}

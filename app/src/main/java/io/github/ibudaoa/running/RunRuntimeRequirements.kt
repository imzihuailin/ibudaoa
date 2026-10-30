package io.github.ibudaoa.running

import kotlinx.serialization.json.*

enum class RuntimeCheckShape { MISSING, NULL, EMPTY_ARRAY, NONEMPTY_ARRAY, EMPTY_STRING, ENCODED_EMPTY_ARRAY, ENCODED_NONEMPTY_ARRAY, OTHER }
data class RunRuntimeRequirements(val runChecks:RuntimeCheckShape=RuntimeCheckShape.MISSING,
    val androidChecks:RuntimeCheckShape=RuntimeCheckShape.MISSING,val gyroscopeRequired:Boolean?=null,
    val verificationSupported:Boolean=false) {
    val automaticSupported:Boolean get()=runChecks in setOf(RuntimeCheckShape.EMPTY_ARRAY,RuntimeCheckShape.ENCODED_EMPTY_ARRAY,RuntimeCheckShape.EMPTY_STRING) &&
        androidChecks==RuntimeCheckShape.EMPTY_ARRAY && gyroscopeRequired!=null && verificationSupported
    val supported:Boolean get()=runChecks in setOf(RuntimeCheckShape.EMPTY_ARRAY,RuntimeCheckShape.ENCODED_EMPTY_ARRAY) &&
        androidChecks==RuntimeCheckShape.EMPTY_ARRAY && gyroscopeRequired!=null && verificationSupported
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
            RunRuntimeRequirements(shape(data["run_check"]),shape(data["android_cheats"]),when(integer("gyroscope_type")) { 0 -> false;1 -> true;else -> null },
                listOf("is_check_geetest","is_check_shumeng","is_check_deepkonw").all { integer(it)==0 } &&
                    (data["robot_module"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull==false }==true)
        }.getOrElse { RunRuntimeRequirements() }
    }
}

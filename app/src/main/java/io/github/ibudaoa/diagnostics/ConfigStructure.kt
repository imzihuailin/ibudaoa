package io.github.ibudaoa.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Types and required-section presence only. Unknown response VALUES never reach disk. */
data class ConfigStructure(val fieldTypes: Map<String, String>, val missingSections: List<String>) {
    companion object {
        private val keyPattern = Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}")
        private val pathPattern = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\[\\])?(?:\\.[a-zA-Z][a-zA-Z0-9_]*(?:\\[\\])?)*")
        private val privateKey = Regex("token|cookie|authorization|password|student|avatar|device|photo|face_img|(^|_)uid($|_)|user_id", RegexOption.IGNORE_CASE)
        private val types = setOf("object", "array", "string", "number", "boolean", "null", "mixed")
        val requiredSections = setOf("school_id", "term_id", "run_zone_id", "time_rule_arr", "run_line_info",
            "run_zone_latlng", "run_zone_latlng_invalid", "run_face_verify_config")

        fun inspect(source: JsonObject): ConfigStructure {
            val result = sortedMapOf<String, String>()
            fun visit(value: JsonElement, path: String, depth: Int) {
                if (depth > 12 || result.size >= 2000) return
                val type = when (value) {
                    JsonNull -> "null"
                    is JsonObject -> "object"
                    is JsonArray -> "array"
                    is JsonPrimitive -> when {
                        value.isString -> "string"
                        value.booleanOrNull != null -> "boolean"
                        else -> "number"
                    }
                }
                if (path.isNotEmpty()) {
                    result[path] = if (result[path]?.let { it != type } == true) "mixed" else type
                }
                when (value) {
                    is JsonObject -> value.forEach { (key, child) ->
                        if (keyPattern.matches(key) && !privateKey.containsMatchIn(key)) {
                            visit(child, if (path.isEmpty()) key else "$path.$key", depth + 1)
                        }
                    }
                    is JsonArray -> value.take(100).forEach { visit(it, "$path[]", depth + 1) }
                    else -> Unit
                }
            }
            visit(source, "", 0)
            return ConfigStructure(result, requiredSections.filter { source[it] == null || source[it] == JsonNull })
        }

        fun fromExport(value: JsonObject): ConfigStructure {
            val fields = (value["field_types"] as? JsonObject).orEmpty().entries.take(2000)
                .filter { (path, type) -> path.length <= 256 && pathPattern.matches(path) &&
                    !privateKey.containsMatchIn(path) && (type as? JsonPrimitive)?.content in types }
                .associate { (path, type) -> path to (type as JsonPrimitive).content }
            val missing = (value["missing_sections"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content }.filter { it in requiredSections }.distinct()
            return ConfigStructure(fields, missing)
        }
    }
}

package io.github.ibudaoa.diagnostics

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class StageVerification { DISABLED, SKIPPED_EMPTY, REQUIRED, UNKNOWN }

enum class FaceConfigShape {
    MISSING, EMPTY_OBJECT, NONEMPTY_OBJECT, UNRECOGNIZED_OBJECT, INVALID;

    companion object {
        val supportedKeys = setOf("start_status", "middle_status", "stop_status", "face_verify_type")

        fun inspect(value: kotlinx.serialization.json.JsonElement?): FaceConfigShape = when (value) {
            null -> MISSING
            is JsonObject -> when {
                value.isEmpty() -> EMPTY_OBJECT
                value.keys.any { it !in supportedKeys } -> UNRECOGNIZED_OBJECT
                value.values.any { (it as? JsonPrimitive)?.intOrNull == null } -> INVALID
                else -> NONEMPTY_OBJECT
            }
            else -> INVALID
        }
    }
}

data class DiagnosticConfig(
    val config: JsonObject,
    val structure: ConfigStructure? = null,
    val faceConfigShape: FaceConfigShape = FaceConfigShape.inspect(config["run_face_verify_config"]),
) {
    val schoolName: String? get() = config["school_name"]?.jsonPrimitive?.contentOrNull
    val campusName: String? get() = config["run_zone_name"]?.jsonPrimitive?.contentOrNull
    val zoneId: Long? get() = config["run_zone_id"]?.jsonPrimitive?.longOrNull
    val ruleCount: Int get() = (config["time_rule_arr"] as? JsonArray)?.size ?: 0
    val candidateCount: Int get() {
        val line = config["run_line_info"] as? JsonObject
        return ((line?.get("point_list") ?: config["point_list"]) as? JsonArray)?.size ?: 0
    }

    // Temporary product policy: only an actually empty object is skipped. Keep its
    // distinction from explicit disabled flags and from lossy/unsupported objects.
    val stageVerification: StageVerification get() {
        val face = config["run_face_verify_config"] as? JsonObject ?: return StageVerification.UNKNOWN
        val stages = listOf("start_status", "middle_status", "stop_status")
            .map { (face[it] as? JsonPrimitive)?.intOrNull }
        return when {
            stages.any { it == 1 } -> StageVerification.REQUIRED
            face.isEmpty() && faceConfigShape == FaceConfigShape.EMPTY_OBJECT -> StageVerification.SKIPPED_EMPTY
            faceConfigShape in setOf(FaceConfigShape.MISSING, FaceConfigShape.INVALID,
                FaceConfigShape.UNRECOGNIZED_OBJECT) -> StageVerification.UNKNOWN
            stages.all { it == 0 } -> StageVerification.DISABLED
            else -> StageVerification.UNKNOWN
        }
    }
}

enum class DiagnosticError(val userMessage: String) {
    TOO_LARGE("文件过大，请使用不超过 2 MB 的配置 JSON。"),
    INVALID_JSON("无法读取配置，请选择有效的跑前配置 JSON。"),
    TOO_DEEP("配置层级过深，未导入任何内容。"),
    NOT_CONFIG("未找到学校和跑区标识，原有配置已保留。"),
}

class DiagnosticException(val reason: DiagnosticError) : Exception(reason.userMessage)

/** Allowlist projection: neither original responses nor unknown string fields are persisted. */
object DiagnosticCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val FORMAT = "ibudaoa-diagnostic-v1"
    private val json = Json { prettyPrint = true }
    private val numeric = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
    private val sensitiveText = Regex("https?://|[0-9]{6,}|[0-9a-fA-F]{24,}", RegexOption.IGNORE_CASE)
    private val rootNumbers = setOf(
        "school_id", "term_id", "run_zone_id", "term_start_time", "term_end_time",
        "run_start_time", "run_end_time", "min_distance", "max_distance", "min_pace",
        "max_pace", "min_log_num", "min_num", "point_num", "point_num_online",
        "point_num_offline", "point_type", "set_point_type", "refresh_point_count",
        "repeat_num", "is_capture_zone", "is_register_face", "is_face", "is_check_face",
        "is_limit_range", "is_check_time", "need_network", "rule_status", "role",
        "android_gps_level", "android_gps_log_precision", "android_gps_max_distance",
        "android_gps_process_precision", "android_gps_start_precision", "authStatus", "auth_num",
        "cache_time", "freerun_status", "gaode_map_type", "gyroscope_type", "in_white_list",
        "is_check_deepkonw", "is_check_device", "is_check_geedevice", "is_check_geetest",
        "is_check_shumeng", "is_location_white_list", "is_show", "is_sim_white_list", "log_logic",
        "log_mode", "offline_gameList_status", "pass_num", "pass_rate", "run_distance",
        "run_log_num", "run_notes_id", "run_notes_status", "run_notes_time", "run_tip_type",
        "run_zone_num", "school_auth_num", "snt_popup_enabled", "stepLength",
        "max_num", "real_point_type", "show_point_num", "random_distance", "capture_type", "capture_interval",
    )
    private val ruleNumbers = setOf(
        "start_time", "end_time", "min_distance", "max_distance", "min_pace", "max_pace",
        "min_log_num", "min_num", "point_num_online", "point_num_offline",
        "score_calc_type",
    )
    private val pointNumbers = setOf(
        "id", "point_id", "latitude", "longitude", "lat", "lng", "type", "is_online",
        "index", "ctrl_status", "is_del", "game_id", "exp", "point_type",
    )
    private val lineNumbers = setOf(
        "point_num", "point_update_distance", "point_min_distance", "point_max_distance1",
        "point_max_distance2", "log_max_distance",
    )
    private val faceNumbers = FaceConfigShape.supportedKeys

    // Preserve only date literals from a decorated label; never save HTML, URLs or surrounding text.
    private fun dateEvidence(label: JsonElement?, saved: JsonElement?): List<String> {
        val result = linkedSetOf<String>()
        fun add(year: Int?, month: Int?, day: Int?) {
            if (year == null || month == null || day == null || year !in 1..9999) return
            runCatching { LocalDate.of(year, month, day).toString() }.getOrNull()?.let { result.add(it) }
        }
        val raw = (label as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= 4096 }
        if (raw != null) Regex("(?<![0-9])([0-9]{4})[-/.年]([0-9]{1,2})[-/.月]([0-9]{1,2})(?:日)?(?![0-9])")
            .findAll(raw).take(32).forEach { add(it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull(), it.groupValues[3].toIntOrNull()) }
        (saved as? JsonArray)?.takeIf { it.size <= 32 }?.forEach {
            val text = (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return@forEach
            if (text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) {
                val parts = text.split('-'); add(parts[0].toIntOrNull(), parts[1].toIntOrNull(), parts[2].toIntOrNull())
            }
        }
        return result.take(32)
    }

    fun decode(text: String): DiagnosticConfig {
        validateInput(text)
        val root = try { json.parseToJsonElement(text) as? JsonObject }
        catch (_: Exception) { throw DiagnosticException(DiagnosticError.INVALID_JSON) }
            ?: throw DiagnosticException(DiagnosticError.INVALID_JSON)
        return project(root)
    }

    internal fun validateInput(text: String) {
        if (text.length > MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            throw DiagnosticException(DiagnosticError.TOO_LARGE)
        }
        checkDepth(text)
    }

    private fun project(root: JsonObject): DiagnosticConfig {
        val source = when {
            (root["format"] as? JsonPrimitive)?.content == FORMAT -> root["config"] as? JsonObject
            root.containsKey("school_id") -> root
            else -> root["data"] as? JsonObject
        } ?: throw DiagnosticException(DiagnosticError.NOT_CONFIG)

        val projected = buildJsonObject {
            projectNumbers(source, rootNumbers).forEach { (key, value) -> put(key, value) }
            listOf("school_name", "run_zone_name").forEach { key ->
                safeLabel(source[key])?.let { put(key, it) }
            }
            (source["robot_module"] as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull != null }
                ?.let { put("robot_module", it) }
            (source["week_time"] as? JsonArray)?.takeIf { array ->
                array.all { (it as? JsonPrimitive)?.intOrNull?.let { day -> day in 0..6 } == true }
            }?.let { put("week_time", it) }
            projectArray(source["running_time"], setOf("start_time", "end_time", "time_type"))
                ?.let { put("running_time", it) }
            (source["dayrule_ids"] as? JsonPrimitive)?.takeIf {
                it.isString && it.content.matches(Regex("[0-9, ]{0,128}"))
            }?.let { put("dayrule_ids", it) }
            (source["term_date"] as? JsonPrimitive)?.takeIf {
                it.isString && it.content.length <= 80 && it.content.all { char ->
                    char in "0123456789-/. ~至年月日—"
                }
            }?.let { put("term_date", it) }
            val dates = dateEvidence(source["term_date"], source["term_date_evidence"])
            if (dates.isNotEmpty()) put("term_date_evidence", JsonArray(dates.map(::JsonPrimitive)))
            projectObject(source["capture_config"], setOf("capture_type", "capture_interval", "start_status", "middle_status", "stop_status"))
                ?.let { put("capture_config", it) }
            projectObject(source["run_face_verify_config"], faceNumbers)?.let {
                put("run_face_verify_config", it)
            }
            projectArray(source["time_rule_arr"], ruleNumbers)?.let { put("time_rule_arr", it) }
            projectArray(source["point_list"], pointNumbers, points = true)?.let { put("point_list", it) }
            (source["run_line_info"] as? JsonObject)?.let { line ->
                put("run_line_info", buildJsonObject {
                    projectNumbers(line, lineNumbers).forEach { (key, value) -> put(key, value) }
                    projectArray(line["point_list"], pointNumbers, points = true)?.let { put("point_list", it) }
                })
            }
            listOf("run_zone_latlng", "run_zone_latlng_invalid").forEach { key ->
                projectCoordinates(source[key])?.let { put(key, it) }
            }
        }
        if ((projected["school_id"] as? JsonPrimitive)?.longOrNull?.let { it > 0 } != true ||
            (projected["run_zone_id"] as? JsonPrimitive)?.longOrNull?.let { it > 0 } != true) {
            throw DiagnosticException(DiagnosticError.NOT_CONFIG)
        }
        val structure = if ((root["format"] as? JsonPrimitive)?.content == FORMAT)
            (root["structure"] as? JsonObject)?.let(ConfigStructure::fromExport) else null
        val rawShape = FaceConfigShape.inspect(source["run_face_verify_config"])
        val savedShape = if ((root["format"] as? JsonPrimitive)?.content == FORMAT && root.containsKey("face_config_shape")) {
            val metadata = root["face_config_shape"] as? JsonPrimitive
            if (metadata?.isString != true) FaceConfigShape.INVALID
            else FaceConfigShape.entries.firstOrNull { it.name == metadata.content } ?: FaceConfigShape.UNRECOGNIZED_OBJECT
        } else null
        val legacyUnsupportedFields = structure?.fieldTypes?.keys.orEmpty().any { path ->
            path.startsWith("run_face_verify_config.") &&
                path.removePrefix("run_face_verify_config.") !in FaceConfigShape.supportedKeys
        }
        val shape = when {
            rawShape == FaceConfigShape.UNRECOGNIZED_OBJECT || legacyUnsupportedFields -> FaceConfigShape.UNRECOGNIZED_OBJECT
            savedShape in setOf(FaceConfigShape.INVALID, FaceConfigShape.MISSING, FaceConfigShape.UNRECOGNIZED_OBJECT) -> savedShape!!
            rawShape == FaceConfigShape.EMPTY_OBJECT && savedShape == FaceConfigShape.NONEMPTY_OBJECT -> savedShape
            else -> rawShape
        }
        return DiagnosticConfig(projected, structure, shape)
    }

    fun encode(config: DiagnosticConfig): String = json.encodeToString(JsonObject.serializer(),
        buildJsonObject {
            put("format", FORMAT)
            put("source", "diagnostic-unverified")
            put("notice", "仅保留已识别配置字段；不含账号凭证，未经平台验证，不能作为开跑依据。")
            // Re-project even if a caller constructed DiagnosticConfig directly.
            put("config", decode(config.config.toString()).config)
            // Preserve source shape across allowlist projection, persistence and re-import.
            // Otherwise an unknown nonempty object could become {} and be falsely skipped.
            put("face_config_shape", config.faceConfigShape.name)
            config.structure?.let { report ->
                val safe = ConfigStructure.fromExport(buildJsonObject {
                    put("field_types", JsonObject(report.fieldTypes.mapValues { JsonPrimitive(it.value) }))
                    put("missing_sections", JsonArray(report.missingSections.map(::JsonPrimitive)))
                })
                put("structure", buildJsonObject {
                    put("field_types", JsonObject(safe.fieldTypes.mapValues { JsonPrimitive(it.value) }))
                    put("missing_sections", JsonArray(safe.missingSections.map(::JsonPrimitive)))
                })
            }
        },
    )

    private fun projectNumbers(source: JsonObject, keys: Set<String>): Map<String, JsonElement> =
        source.mapNotNull { (key, value) ->
            if (key !in keys) return@mapNotNull null
            val primitive = value as? JsonPrimitive ?: return@mapNotNull null
            val content = primitive.content
            if (content.length > 24 || !numeric.matches(content)) return@mapNotNull null
            // A numeric JSON element prevents arbitrary strings from crossing the boundary.
            key to json.parseToJsonElement(content)
        }.toMap()

    private fun projectObject(value: JsonElement?, keys: Set<String>): JsonObject? =
        (value as? JsonObject)?.let { JsonObject(projectNumbers(it, keys)) }

    private fun projectArray(value: JsonElement?, keys: Set<String>, points: Boolean = false): JsonArray? =
        (value as? JsonArray)?.let { array ->
            JsonArray(array.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                buildJsonObject {
                    projectNumbers(entry, keys).forEach { (key, number) -> put(key, number) }
                    if (points) {
                        listOf("id", "point_id").forEach { key ->
                            val id = entry[key] as? JsonPrimitive
                            if (id?.isString == true && id.content.matches(Regex("[A-Za-z0-9_-]{1,64}"))) put(key, id)
                        }
                        safeLabel(entry["address"])?.let { put("address", it) }
                        val coordinate = (entry["jingwei"] as? JsonPrimitive)?.content
                        if (coordinate != null && coordinate.length <= 96 &&
                            coordinate.all { it in "0123456789.,-+ " } && coordinate.split(',').let { pair ->
                                pair.size == 2 && pair.all { part -> part.trim().toDoubleOrNull()?.let { number ->
                                    number.isFinite() && number in -180.0..180.0
                                } == true }
                            }) put("jingwei", coordinate)
                    }
                }
            })
        }

    private fun safeLabel(value: JsonElement?): JsonPrimitive? {
        val primitive = value as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        val content = primitive.content.trim()
        if (content.isEmpty() || content.length > 80 || content.any { it.isISOControl() }) return null
        return JsonPrimitive(if (sensitiveText.containsMatchIn(content)) "[已隐藏]" else content)
    }

    private fun projectCoordinates(value: JsonElement?): JsonArray? = (value as? JsonArray)?.let { array ->
        JsonArray(array.mapNotNull { entry ->
            val text = (entry as? JsonPrimitive)?.content ?: return@mapNotNull null
            if (text.length in 1..2048 && text.all { it in "0123456789.,-+ []" }) entry else null
        })
    }

    private fun checkDepth(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { char ->
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 32) throw DiagnosticException(DiagnosticError.TOO_DEEP)
                '}', ']' -> depth--
            }
        }
    }
}

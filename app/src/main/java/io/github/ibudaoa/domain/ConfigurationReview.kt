package io.github.ibudaoa.domain

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.diagnostics.StageVerification
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs

/** Platform coordinate order is known here; its CRS and road alignment remain unverified. */
data class CampusCoordinate(val latitude: Double, val longitude: Double)

data class CampusCandidate(
    val id: String,
    val coordinate: CampusCoordinate,
    val online: Boolean,
    val type: Int,
    val pointType: Int,
)

data class CampusTimeRule(
    val startSecond: Int,
    val endSecond: Int,
    val minimumKm: Double,
    val maximumKm: Double,
    val fastestSecondsPerKm: Int,
    val slowestSecondsPerKm: Int,
    val requiredCheckIns: Int,
    val onlinePoints: Int,
    val offlinePoints: Int,
) {
    // Official N3 includes both boundaries; overlapping matches are reported separately below.
    fun contains(secondOfDay: Int) = secondOfDay in startSecond..endSecond
}

enum class ConfigurationIssue(val label: String) {
    RULES("时段规则缺失或格式不支持"),
    CANDIDATES("候选点缺失、重复或坐标无效"),
    ALLOWED_REGION("跑区边界缺失或坐标无效"),
    FORBIDDEN_REGION("禁用区域格式仍需核对"),
    REGION_RELATION("部分官方候选点位于配置边界外，需核对规则"),
    FACE_UNKNOWN("阶段核验要求未知"),
    FACE_REQUIRED("本次要求核验，首版不支持"),
    ADDITIONAL_RULES("日期、每周及其他限制仍待核对"),
    ROAD_NETWORK("道路与坐标系尚未验证"),
}

data class ConfigurationReview(
    val rules: List<CampusTimeRule>?,
    val candidates: List<CampusCandidate>?,
    val allowedBoundary: List<CampusCoordinate>?,
    val forbiddenRegionIsEmpty: Boolean?,
    val pointRefreshMeters: Double?,
    val outsideBoundaryIds: Set<String>?,
    val issues: Set<ConfigurationIssue>,
) {
    fun matchingRules(secondOfDay: Int): List<CampusTimeRule> {
        require(secondOfDay in 0..86399)
        return rules.orEmpty().filter { it.contains(secondOfDay) }
    }

    // A unique match describes the time rule, never permission to start a task.
    fun uniqueTimeRule(secondOfDay: Int): CampusTimeRule? = matchingRules(secondOfDay).singleOrNull()
}

/** Read-only interpretation; no selection, target generation, routing or platform writes. */
object ConfigurationReviewer {
    fun review(config: DiagnosticConfig): ConfigurationReview {
        val root = config.config
        val issues = linkedSetOf(ConfigurationIssue.ADDITIONAL_RULES, ConfigurationIssue.ROAD_NETWORK)
        fun <T> checked(issue: ConfigurationIssue, operation: () -> T): T? = try { operation() }
            catch (_: InvalidConfiguration) { issues.add(issue); null }

        val rules = checked(ConfigurationIssue.RULES) {
            val array = root["time_rule_arr"] as? JsonArray ?: invalid()
            if (array.isEmpty()) invalid()
            array.map { entry ->
                val rule = entry as? JsonObject ?: invalid()
                val start = rule.integer("start_time")
                val end = rule.integer("end_time")
                val minimum = rule.number("min_distance")
                val maximum = rule.number("max_distance")
                // Names describe speed, not increasing duration: max_pace is the faster bound.
                val fastest = rule.integer("max_pace")
                val slowest = rule.integer("min_pace")
                val count = rule.integer("min_log_num")
                val online = rule.integer("point_num_online")
                val offline = rule.integer("point_num_offline")
                if (start !in 0..86399 || end !in start..86400 || minimum <= 0 || maximum < minimum ||
                    fastest <= 0 || slowest < fastest || count < 0 || online < 0 || offline < 0) invalid()
                CampusTimeRule(start, end, minimum, maximum, fastest, slowest, count, online, offline)
            }
        }
        val line = root["run_line_info"] as? JsonObject
        val candidates = checked(ConfigurationIssue.CANDIDATES) {
            // Do not merge an absent/current line's point table with another source.
            val array = line?.get("point_list") as? JsonArray ?: invalid()
            if (array.isEmpty()) invalid()
            val result = array.map { entry ->
                val point = entry as? JsonObject ?: invalid()
                val identifier = point["id"] ?: point["point_id"] ?: invalid()
                val id = (identifier as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content ?: invalid()
                if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) invalid()
                val alternate = point["point_id"] as? JsonPrimitive
                if (point.containsKey("id") && alternate != null && alternate.content != id) invalid()
                val online = point.integer("is_online")
                if (online !in 0..1) invalid()
                val coordinate = coordinate((point["jingwei"] as? JsonPrimitive)?.content ?: invalid(), latitudeFirst = true)
                CampusCandidate(id, coordinate, online == 1, point.integer("type"), point.integer("point_type"))
            }
            if (result.map { it.id }.distinct().size != result.size) invalid()
            result
        }
        val boundary = checked(ConfigurationIssue.ALLOWED_REGION) {
            val array = root["run_zone_latlng"] as? JsonArray ?: invalid()
            if (array.size < 3) invalid()
            val result = array.map { coordinate((it as? JsonPrimitive)?.content ?: invalid(), latitudeFirst = false) }
            if (result.distinct().size < 3) invalid()
            val area = result.indices.sumOf { i ->
                val a = result[i]; val b = result[(i + 1) % result.size]
                a.longitude * b.latitude - b.longitude * a.latitude
            }
            if (abs(area) <= 1e-12) invalid()
            result
        }
        val forbidden = (root["run_zone_latlng_invalid"] as? JsonArray)?.let { if (it.isEmpty()) true else null }
        if (forbidden == null) issues.add(ConfigurationIssue.FORBIDDEN_REGION)
        when (config.stageVerification) {
            StageVerification.UNKNOWN -> issues.add(ConfigurationIssue.FACE_UNKNOWN)
            StageVerification.REQUIRED -> issues.add(ConfigurationIssue.FACE_REQUIRED)
            StageVerification.DISABLED, StageVerification.SKIPPED_EMPTY -> Unit
        }
        val refresh = (line?.get("point_update_distance") as? JsonPrimitive)?.doubleOrNull
            ?.takeIf { it.isFinite() && it >= 0 }?.times(1000)?.takeIf { it.isFinite() }
        val outside = if (candidates != null && boundary != null)
            candidates.filterNot { insideOrOnBoundary(it.coordinate, boundary) }.map { it.id }.toSet() else null
        if (!outside.isNullOrEmpty()) issues.add(ConfigurationIssue.REGION_RELATION)
        // Preserve every official candidate: a geometry discrepancy cannot authorize replacement.
        return ConfigurationReview(rules, candidates, boundary, forbidden, refresh, outside, issues)
    }

    private fun coordinate(text: String, latitudeFirst: Boolean): CampusCoordinate {
        val parts = text.split(',')
        if (parts.size != 2) invalid()
        val first = parts[0].trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: invalid()
        val second = parts[1].trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: invalid()
        val latitude = if (latitudeFirst) first else second
        val longitude = if (latitudeFirst) second else first
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) invalid()
        return CampusCoordinate(latitude, longitude)
    }

    private fun JsonObject.integer(key: String): Int = (get(key) as? JsonPrimitive)?.intOrNull ?: invalid()
    private fun JsonObject.number(key: String): Double = (get(key) as? JsonPrimitive)?.doubleOrNull
        ?.takeIf { it.isFinite() } ?: invalid()
    private class InvalidConfiguration : Exception()
    private fun invalid(): Nothing = throw InvalidConfiguration()

    private fun insideOrOnBoundary(point: CampusCoordinate, polygon: List<CampusCoordinate>): Boolean {
        var inside = false
        polygon.indices.forEach { i ->
            val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]
            val cross = (point.longitude - a.longitude) * (b.latitude - a.latitude) -
                (point.latitude - a.latitude) * (b.longitude - a.longitude)
            if (abs(cross) <= 1e-10 && point.longitude in minOf(a.longitude, b.longitude)..maxOf(a.longitude, b.longitude) &&
                point.latitude in minOf(a.latitude, b.latitude)..maxOf(a.latitude, b.latitude)) return true
            if ((a.latitude > point.latitude) != (b.latitude > point.latitude) &&
                point.longitude < (b.longitude - a.longitude) * (point.latitude - a.latitude) /
                    (b.latitude - a.latitude) + a.longitude) inside = !inside
        }
        return inside
    }
}

package io.github.ibudaoa.data

import io.github.ibudaoa.diagnostics.DiagnosticCodec
import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.domain.PlatformOrigin
import io.github.ibudaoa.domain.PointScope
import io.github.ibudaoa.domain.PointSelectionCache
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class InvalidPointCache : Exception("本地点集缓存无效")

/** Private app storage only. Never add this payload (especially the origin) to diagnostic exports. */
object PointCacheCodec {
    private const val MAX_BYTES = 64 * 1024

    fun encode(cache: PointSelectionCache): String {
        validate(cache)
        val result = buildJsonObject {
            put("version", 1)
            put("connection_epoch", cache.scope.connectionEpoch)
            put("school_id", cache.scope.schoolId); put("term_id", cache.scope.termId); put("zone_id", cache.scope.zoneId)
            put("policy_fingerprint", cache.scope.policyFingerprint)
            put("selected_on", cache.selectedOn.toString())
            put("origin_latitude", cache.origin.coordinate.latitude)
            put("origin_longitude", cache.origin.coordinate.longitude)
            put("selected_ids", JsonArray(cache.selectedIds.map(::JsonPrimitive)))
            put("confirmed_ids", JsonArray(cache.confirmedIds.sorted().map(::JsonPrimitive)))
            put("locked", cache.locked)
            put("execution_lock_id", cache.executionLockId?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()
        if (result.toByteArray().size > MAX_BYTES) throw InvalidPointCache()
        return result
    }

    fun decode(text: String): PointSelectionCache = try {
        if (text.length > MAX_BYTES || text.toByteArray().size > MAX_BYTES) throw InvalidPointCache()
        DiagnosticCodec.validateInput(text)
        val root = Json.parseToJsonElement(text) as? JsonObject ?: throw InvalidPointCache()
        if ((root["version"] as? JsonPrimitive)?.intOrNull != 1) throw InvalidPointCache()
        val cache = PointSelectionCache(
            scope = PointScope(root.long("connection_epoch"), root.long("school_id"), root.long("term_id"),
                root.long("zone_id"), root.text("policy_fingerprint")),
            selectedOn = LocalDate.parse(root.text("selected_on")),
            origin = PlatformOrigin(CampusCoordinate(root.number("origin_latitude"), root.number("origin_longitude"))),
            selectedIds = root.ids("selected_ids"),
            confirmedIds = root.ids("confirmed_ids").toSet(),
            locked = (root["locked"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw InvalidPointCache(),
            executionLockId = if (root["execution_lock_id"] == JsonNull) null else root.text("execution_lock_id"),
        )
        validate(cache)
        cache
    } catch (_: Exception) { throw InvalidPointCache() }

    private fun validate(cache: PointSelectionCache) {
        val scope = cache.scope
        if (scope.connectionEpoch <= 0 || scope.schoolId <= 0 || scope.termId <= 0 || scope.zoneId <= 0 ||
            !scope.policyFingerprint.matches(Regex("[0-9a-f]{64}")) || cache.selectedIds.isEmpty() ||
            cache.selectedIds.size > 2048 || cache.selectedIds.distinct().size != cache.selectedIds.size ||
            cache.selectedIds.any { !it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ||
            cache.confirmedIds.any { it !in cache.selectedIds } || (!cache.locked && cache.confirmedIds.isNotEmpty())) {
            throw InvalidPointCache()
        }
        if (cache.locked) {
            if (cache.executionLockId?.matches(Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) != true) throw InvalidPointCache()
        } else if (cache.executionLockId != null) throw InvalidPointCache()
        val origin = cache.origin.coordinate
        if (!origin.latitude.isFinite() || !origin.longitude.isFinite() ||
            origin.latitude !in -90.0..90.0 || origin.longitude !in -180.0..180.0) throw InvalidPointCache()
    }

    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: throw InvalidPointCache()
    private fun JsonObject.long(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: throw InvalidPointCache()
    private fun JsonObject.number(key: String): Double = (get(key) as? JsonPrimitive)?.doubleOrNull ?: throw InvalidPointCache()
    private fun JsonObject.ids(key: String): List<String> {
        val array = get(key) as? JsonArray ?: throw InvalidPointCache()
        val result = array.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: throw InvalidPointCache() }
        if (result.distinct().size != result.size) throw InvalidPointCache()
        return result
    }
}

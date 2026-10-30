package io.github.ibudaoa.domain

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.random.Random

enum class PointSelectionProblem(val label: String) {
    UNSUPPORTED_MODE("当前选点模式尚未适配"),
    INVALID_CONFIGURATION("选点配置缺失或不一致"),
    NO_UNIQUE_TIME_RULE("当前没有唯一适用的时段规则"),
    LOCATION_REQUIRED("需要已对齐平台坐标系的位置"),
    DISTANCE_UNAVAILABLE("平台距离计算尚未就绪"),
    INSUFFICIENT_POINTS("符合当前分支要求的候选点不足"),
    OUTSIDE_DISTANCE_GATE("当前位置未通过官方候选距离检查"),
    ACTIVE_CACHE_MISSING("运行中的点集缺失，需要核对任务状态"),
    ACTIVE_CONFIGURATION_CHANGED("配置已改变，需要核对任务状态"),
    ACTIVE_DATE_CHANGED("任务已跨日期，需要核对规则与平台状态"),
    INVALID_ACTIVE_CACHE("运行中的点集不一致，需要核对任务状态"),
    LOCKED_CACHE("已有锁定点集，须先核对任务状态"),
}

class PointSelectionException(val problem: PointSelectionProblem) : Exception(problem.label)

data class PointScope(
    val connectionEpoch: Long,
    val schoolId: Long,
    val termId: Long,
    val zoneId: Long,
    val policyFingerprint: String,
)

/** The origin must be in the SAME frame as platform points. Raw Android GPS is not an adapter. */
data class PlatformOrigin(val coordinate: CampusCoordinate)

/** Implement with the verified platform/map SDK before connecting this engine to execution. */
interface PlatformDistance {
    val implementationId: String
    fun meters(from: CampusCoordinate, to: CampusCoordinate): Double
}

class PointSelectionPolicy internal constructor(
    val schoolId: Long,
    val termId: Long,
    val zoneId: Long,
    val rule: CampusTimeRule,
    val candidates: List<CampusCandidate>,
    val minimumOriginDistanceMeters: Double,
    val maximumRankDistanceMeters: Double,
    val refreshDistanceMeters: Double,
    val distanceImplementationId: String,
    val fingerprint: String,
) {
    val count: Int get() = rule.requiredCheckIns
    fun scope(connectionEpoch: Long): PointScope {
        if (connectionEpoch <= 0) throw PointSelectionException(PointSelectionProblem.INVALID_CONFIGURATION)
        return PointScope(connectionEpoch, schoolId, termId, zoneId, fingerprint)
    }
}

/** Exact supported subset of official 4.3.1 f(): student, random, normal online-only, no repeats. */
object PointPolicyFactory {
    fun create(config: DiagnosticConfig, secondOfDay: Int, distanceImplementationId: String): PointSelectionPolicy {
        val root = config.config
        if (distanceImplementationId.isBlank() || distanceImplementationId.length > 80 || secondOfDay !in 0..86399) invalid()
        if (root.integer("role") != 1 || root.integer("set_point_type") != 1 || root.integer("point_type") != 1 ||
            root.integer("repeat_num") != 0 || root.integer("log_mode") != 1 || root.integer("is_limit_range") != 0) {
            throw PointSelectionException(PointSelectionProblem.UNSUPPORTED_MODE)
        }
        val review = ConfigurationReviewer.review(config)
        val rule = review.uniqueTimeRule(secondOfDay)
            ?: throw PointSelectionException(PointSelectionProblem.NO_UNIQUE_TIME_RULE)
        // Single-point/mandatory/offline/repeated branches have different official algorithms.
        if (rule.requiredCheckIns < 2 || rule.offlinePoints != 0 || rule.onlinePoints != rule.requiredCheckIns) {
            throw PointSelectionException(PointSelectionProblem.UNSUPPORTED_MODE)
        }
        val candidates = review.candidates ?: invalid()
        if (candidates.any { it.type != 2 || it.pointType != 1 || !it.online }) {
            throw PointSelectionException(PointSelectionProblem.UNSUPPORTED_MODE)
        }
        val line = root["run_line_info"] as? JsonObject ?: invalid()
        val rawPoints = line["point_list"] as? JsonArray ?: invalid()
        if (rawPoints.any {
                val point = it as? JsonObject ?: invalid()
                point.integer("is_del") != 0 || point.integer("ctrl_status") != 1
            }) throw PointSelectionException(PointSelectionProblem.UNSUPPORTED_MODE)
        val minimum = line.distance("point_min_distance")
        val near = line.distance("point_max_distance1")
        val maximum = line.distance("point_max_distance2")
        val refresh = line.distance("point_update_distance")
        if (maximum < minimum || near > maximum) invalid()
        val schoolId = root.positiveId("school_id")
        val termId = root.positiveId("term_id")
        val zoneId = root.positiveId("run_zone_id")
        val identity = buildJsonObject {
            put("algorithm", "official-4.3.1-student-normal-online-random-v1")
            put("distance", distanceImplementationId)
            put("school", schoolId); put("term", termId); put("zone", zoneId)
            put("start", rule.startSecond); put("end", rule.endSecond)
            put("minimum_km", rule.minimumKm); put("maximum_km", rule.maximumKm)
            put("fast", rule.fastestSecondsPerKm); put("slow", rule.slowestSecondsPerKm)
            put("count", rule.requiredCheckIns)
            put("minimum_origin_m", minimum); put("near_m", near); put("maximum_rank_m", maximum); put("refresh_m", refresh)
            put("points", JsonArray(candidates.sortedBy { it.id }.map { point -> buildJsonObject {
                put("id", point.id); put("lat", point.coordinate.latitude); put("lng", point.coordinate.longitude)
                put("online", point.online); put("type", point.type); put("point_type", point.pointType)
            } }))
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(identity.toString().toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return PointSelectionPolicy(schoolId, termId, zoneId, rule, candidates.toList(), minimum, maximum, refresh,
            distanceImplementationId, fingerprint)
    }

    private fun JsonObject.integer(key: String): Int = (get(key) as? JsonPrimitive)?.intOrNull ?: invalid()
    private fun JsonObject.positiveId(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: invalid()
    private fun JsonObject.distance(key: String): Double {
        val kilometers = (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 } ?: invalid()
        // Source fields and multiplications are floats; retain their comparison boundaries.
        return (kilometers.toFloat() * 1000f).toDouble().takeIf { it.isFinite() } ?: invalid()
    }
    private fun invalid(): Nothing = throw PointSelectionException(PointSelectionProblem.INVALID_CONFIGURATION)
}

enum class PointUsePhase { PRE_START, RUNNING, PAUSED, INTERRUPTED }
enum class PointRefreshReason { FIRST_SELECTION, NEW_CONNECTION_OR_CONFIGURATION, NEW_DAY, MOVED, INVALID_PREPARATION_CACHE }

data class PointSelectionCache(
    val scope: PointScope,
    val selectedOn: LocalDate,
    val origin: PlatformOrigin,
    val selectedIds: List<String>,
    val confirmedIds: Set<String> = emptySet(),
    val locked: Boolean = false,
    val executionLockId: String? = null,
) {
    fun lockForExecution(): PointSelectionCache {
        check(!locked)
        // Local per-attempt identity, not a credential/device ID or a platform record ID.
        return copy(locked = true, executionLockId = UUID.randomUUID().toString())
    }

    /** Caller must have a real platform confirmation. This is not GPS-based local success. */
    fun withPlatformConfirmedPoint(id: String): PointSelectionCache {
        require(locked && executionLockId != null && id in selectedIds)
        return copy(confirmedIds = confirmedIds + id)
    }
}

sealed interface PointResolution {
    data class Selected(val cache: PointSelectionCache, val reason: PointRefreshReason) : PointResolution
    data class Reused(val cache: PointSelectionCache) : PointResolution
    data class Blocked(val problem: PointSelectionProblem) : PointResolution
}

class PointSelectionEngine(private val distance: PlatformDistance, private val random: Random = Random.Default) {
    fun resolve(
        policy: PointSelectionPolicy,
        connectionEpoch: Long,
        day: LocalDate,
        origin: PlatformOrigin?,
        cached: PointSelectionCache?,
        phase: PointUsePhase = PointUsePhase.PRE_START,
    ): PointResolution {
        if (policy.distanceImplementationId != distance.implementationId) {
            return PointResolution.Blocked(PointSelectionProblem.DISTANCE_UNAVAILABLE)
        }
        val scope = try { policy.scope(connectionEpoch) }
            catch (error: PointSelectionException) { return PointResolution.Blocked(error.problem) }
        val active = phase != PointUsePhase.PRE_START
        if (active) {
            if (cached == null) return PointResolution.Blocked(PointSelectionProblem.ACTIVE_CACHE_MISSING)
            if (cached.scope != scope) return PointResolution.Blocked(PointSelectionProblem.ACTIVE_CONFIGURATION_CHANGED)
            if (!validCache(cached, policy) || !cached.locked) return PointResolution.Blocked(PointSelectionProblem.INVALID_ACTIVE_CACHE)
            if (cached.selectedOn != day) return PointResolution.Blocked(PointSelectionProblem.ACTIVE_DATE_CHANGED)
            return PointResolution.Reused(cached)
        }
        if (cached?.locked == true) return PointResolution.Blocked(PointSelectionProblem.LOCKED_CACHE)
        if (origin == null || !validOrigin(origin)) return PointResolution.Blocked(PointSelectionProblem.LOCATION_REQUIRED)
        val reason = when {
            cached == null -> PointRefreshReason.FIRST_SELECTION
            cached.scope != scope -> PointRefreshReason.NEW_CONNECTION_OR_CONFIGURATION
            cached.selectedOn != day -> PointRefreshReason.NEW_DAY
            !validCache(cached, policy) || cached.confirmedIds.isNotEmpty() -> PointRefreshReason.INVALID_PREPARATION_CACHE
            else -> {
                val moved = measure(cached.origin.coordinate, origin.coordinate)
                    ?: return PointResolution.Blocked(PointSelectionProblem.DISTANCE_UNAVAILABLE)
                if (moved <= policy.refreshDistanceMeters) return PointResolution.Reused(cached)
                PointRefreshReason.MOVED
            }
        }
        val ranked = policy.candidates.map { candidate ->
            val meters = measure(origin.coordinate, candidate.coordinate)
                ?: return PointResolution.Blocked(PointSelectionProblem.DISTANCE_UNAVAILABLE)
            candidate to meters
        }.filter { (_, meters) -> meters >= policy.minimumOriginDistanceMeters }.sortedBy { it.second }
        if (ranked.size < policy.count) return PointResolution.Blocked(PointSelectionProblem.INSUFFICIENT_POINTS)
        // f() checks the count-th nearest point, then samples the WHOLE normal pool.
        // Do not turn this into nearest-N, radius filtering, or a route-length optimization.
        if (ranked[policy.count - 1].second > policy.maximumRankDistanceMeters) {
            return PointResolution.Blocked(PointSelectionProblem.OUTSIDE_DISTANCE_GATE)
        }
        val pool = ranked.map { it.first.id }.toMutableList()
        repeat(policy.count) { i ->
            val selected = i + random.nextInt(pool.size - i)
            val value = pool[i]; pool[i] = pool[selected]; pool[selected] = value
        }
        val ids = pool.take(policy.count).shuffled(random)
        return PointResolution.Selected(PointSelectionCache(scope, day, origin, ids), reason)
    }

    private fun measure(from: CampusCoordinate, to: CampusCoordinate): Double? =
        try { distance.meters(from, to).takeIf { it.isFinite() && it >= 0 } } catch (_: Exception) { null }

    private fun validCache(cache: PointSelectionCache, policy: PointSelectionPolicy): Boolean =
        validOrigin(cache.origin) && cache.selectedIds.size == policy.count &&
            (if (cache.locked) cache.executionLockId?.matches(Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) == true
                else cache.executionLockId == null) &&
            cache.selectedIds.distinct().size == cache.selectedIds.size &&
            cache.selectedIds.all { id -> policy.candidates.any { it.id == id } } &&
            cache.confirmedIds.all { it in cache.selectedIds }

    private fun validOrigin(origin: PlatformOrigin): Boolean = origin.coordinate.let {
        it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
    }
}

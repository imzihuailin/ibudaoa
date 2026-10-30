package io.github.ibudaoa.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import io.github.ibudaoa.roads.*
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ibudaoa.diagnostics.DiagnosticCodec
import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.domain.PointSelectionCache
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

private val Context.diagnosticStore by preferencesDataStore("diagnostic")

data class PointPlanningStorage(
    val config: DiagnosticConfig?,
    val captured: Boolean,
    val connectionEpoch: Long,
    val cache: PointSelectionCache?,
    val captureRevision: Int = 0,
    val roadReport: RoadBatchReport? = null,
    val capturedAtEpochSecond: Long = 0,
    val roadEvidence: RoadEvidenceSnapshot? = null,
    val gapEvidence: RoadGapEvidence? = null,
    val manualGaps: ManualRoadGaps? = null,
    val reviewedDirections: ReviewedRoadDirections? = null,
)

class DiagnosticStore internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.diagnosticStore)
    private val configKey = stringPreferencesKey("sanitized_config")
    private val preparationKey = booleanPreferencesKey("foreground_preparation_acknowledged")
    private val capturedKey = booleanPreferencesKey("config_captured_from_official")
    private val epochKey = longPreferencesKey("local_connection_epoch")
    private val pointCacheKey = stringPreferencesKey("private_point_selection_cache")
    private val captureRevisionKey = intPreferencesKey("safe_capture_revision")
    private val capturedAtKey = longPreferencesKey("config_capture_device_epoch_second")
    private val roadEvidenceKey = stringPreferencesKey("private_candidate_road_evidence_v1")
    private val roadReportKey = stringPreferencesKey("candidate_road_check")
    private val gapEvidenceKey = stringPreferencesKey("private_road_gap_evidence_v1")
    private val manualGapsKey = stringPreferencesKey("private_manual_road_gaps_v1")
    private val reviewedDirectionsKey = stringPreferencesKey("private_reviewed_road_directions_v1")
    private val mapConsentKey = booleanPreferencesKey("amap_privacy_consent_v1")

    val mapConsent: Flow<Boolean> = store.data.map { it[mapConsentKey] == true }
    suspend fun setMapConsent(agreed: Boolean) { store.edit { it[mapConsentKey] = agreed } }

    val config: Flow<DiagnosticConfig?> = store.data.map { preferences ->
        preferences[configKey]?.let { saved -> runCatching { DiagnosticCodec.decode(saved) }.getOrNull() }
    }
    val preparationAcknowledged: Flow<Boolean> = store.data.map { it[preparationKey] == true }
    val captured: Flow<Boolean> = store.data.map { it[capturedKey] == true }
    // One emission contains a consistent config/connection/cache revision, never combine separate flows for planning.
    val pointPlanningStorage: Flow<PointPlanningStorage> = store.data.map { preferences ->
        val decoded = preferences[configKey]?.let { runCatching { DiagnosticCodec.decode(it) }.getOrNull() }
        val report = preferences.readRoadReport(decoded)
        val roads = preferences.readRoadEvidence(decoded, report)
        PointPlanningStorage(
            config = decoded,
            captured = preferences[capturedKey] == true && decoded != null,
            connectionEpoch = preferences.epoch(),
            cache = preferences[pointCacheKey]?.let { runCatching { PointCacheCodec.decode(it) }.getOrNull() },
            captureRevision = preferences[captureRevisionKey] ?: 0,
            roadReport = report,
            roadEvidence = roads,
            gapEvidence = preferences.readGapEvidence(roads),
            manualGaps = preferences.readManualGaps(roads),
            reviewedDirections = preferences.readDirections(roads),
            capturedAtEpochSecond = preferences[capturedAtKey] ?: 0,
        )
    }

    /** Explicit capture/reconnect/import only; future within-account rule refresh needs a separate API. */
    suspend fun save(config: DiagnosticConfig, captured: Boolean = false) {
        persist(config,captured,null)
    }
    private class OnlineRefresh(val expected:DiagnosticConfig,val epoch:Long,val current:()->Boolean)
    internal suspend fun refreshVerified(config:DiagnosticConfig,expected:DiagnosticConfig,epoch:Long,current:()->Boolean): PointPlanningStorage {
        persist(config,true,OnlineRefresh(expected,epoch,current))
        val committed = pointPlanningStorage.first()
        check(current() && committed.config == io.github.ibudaoa.running.RunningRoadSnapshot.canonical(config) &&
            committed.connectionEpoch == nextEpoch(epoch)) { "在线配置或账号状态已变化，停止准备" }
        return committed
    }
    private suspend fun persist(config:DiagnosticConfig,captured:Boolean,online:OnlineRefresh?) {
        // The raw input never reaches disk; persist only the allowlisted export.
        store.edit {
            requireUnlocked(it)
            val previous = it[configKey]?.let { saved -> runCatching { DiagnosticCodec.decode(saved) }.getOrNull() }
            if(online!=null) {
                check(online.current() && previous==online.expected && it.epoch()==online.epoch) { "在线配置或账号状态已变化，原数据已保留" }
                check(io.github.ibudaoa.running.OnlineConfigurationCompatibility.compatible(previous!!,config)) {
                    "学校或道路配置已变化，原有道路已保留"
                }
            }
            // Candidate-road geometry is public campus data; retain it only for an identical complete
            // projected configuration. This does not assert account identity or retain selected points.
            val sameCapturedConfig = captured && it[capturedKey] == true &&
                it[captureRevisionKey] == CURRENT_CAPTURE_REVISION && (previous == config || online!=null)
            // Do not discard a person's explicit road review when a different capture or import arrives.
            if (!sameCapturedConfig) {
                check(it[manualGapsKey] == null && it[reviewedDirectionsKey] == null) {
                    "新配置与已确认的道路不同，原有数据已保留"
                }
            }
            val oldReport = if (sameCapturedConfig) it.readRoadReport(previous) else null
            val oldRoads = if (sameCapturedConfig) it.readRoadEvidence(previous, oldReport) else null
            val oldGaps = if (sameCapturedConfig) it.readGapEvidence(oldRoads) else null
            val oldManual = if (sameCapturedConfig) it.readManualGaps(oldRoads) else null
            val oldDirections = if (sameCapturedConfig) it.readDirections(oldRoads) else null
            check(it[reviewedDirectionsKey] == null || oldDirections != null) { "已有道路方向确认无法核对" }
            check(it[manualGapsKey] == null || oldManual != null) { "已有道路确认无法核对，原有数据已保留" }
            val nextEpoch = nextEpoch(it.epoch())
            val nextPlan = if (oldReport != null) runCatching { RoadBatchPlan.create(config, nextEpoch) }.getOrNull() else null
            it[epochKey] = nextEpoch
            it[configKey] = DiagnosticCodec.encode(config)
            it[capturedKey] = captured
            it[captureRevisionKey] = if (captured) CURRENT_CAPTURE_REVISION else 0
            it[capturedAtKey] = if (captured) System.currentTimeMillis() / 1000 else 0
            if (oldReport != null && nextPlan != null && oldReport.candidateCount == nextPlan.candidateCount) {
                val report = oldReport.copy(scope = nextPlan.scope)
                it[roadReportKey] = RoadBatchCodec.text(report)
                if (oldRoads != null) {
                    val roads = oldRoads.copy(scope = nextPlan.scope)
                    it[roadEvidenceKey] = RoadEvidenceCodec.encode(roads)
                    if (oldGaps != null) {
                        val gaps = oldGaps.copy(scope = nextPlan.scope, evidenceFingerprint = RoadGapPlan.fingerprint(roads))
                        it[gapEvidenceKey] = RoadGapEvidenceCodec.encode(gaps)
                    } else it.remove(gapEvidenceKey)
                    // User confirmations are transferable only if the entire ordered gap list is identical.
                    val rebase = if (oldManual != null) runCatching {
                        val metric = io.github.ibudaoa.maps.AmapDistance()
                        val original = RoadGapPlan.create(previous!!, oldRoads, metric)
                        val replacement = RoadGapPlan.create(config, roads, metric)
                        check(original.evidenceFingerprint == oldManual.evidenceFingerprint &&
                            original.gaps == replacement.gaps && oldManual.indices.all { index -> index in replacement.gaps.indices })
                        ManualRoadGaps(replacement.evidenceFingerprint, oldManual.indices)
                    }.getOrNull() else null
                    check(oldManual == null || rebase != null) { "已有道路确认无法迁移，原有数据已保留" }
                    if (rebase == null) it.remove(manualGapsKey) else it[manualGapsKey] = ManualRoadGapsCodec.encode(rebase)
                    if (oldDirections != null) {
                        val oldGraph = ObservedRoadGraphBuilder.build(previous!!, oldRoads, io.github.ibudaoa.maps.AmapDistance(), oldGaps, oldManual)
                        ReviewedRoadDirectionsBuilder.apply(oldGraph, oldDirections, oldManual)
                        val newGraph = ObservedRoadGraphBuilder.build(config, roads, io.github.ibudaoa.maps.AmapDistance(),
                            oldGaps?.copy(scope = roads.scope, evidenceFingerprint = RoadGapPlan.fingerprint(roads)), rebase)
                        val replacement = oldDirections.copy(scope = roads.scope, evidenceFingerprint = RoadGapPlan.fingerprint(roads),
                            manualGapsSnapshot = rebase?.let(ManualRoadGapsCodec::encode))
                        ReviewedRoadDirectionsBuilder.apply(newGraph, replacement, rebase)
                        it[reviewedDirectionsKey] = ReviewedRoadDirectionsCodec.encode(replacement)
                    } else it.remove(reviewedDirectionsKey)
                } else { it.remove(roadEvidenceKey); it.remove(gapEvidenceKey); it.remove(manualGapsKey) }
            } else {
                check(oldManual == null && oldDirections == null) { "已有道路确认无法迁移，原有数据已保留" }
                it.remove(roadReportKey); it.remove(roadEvidenceKey); it.remove(gapEvidenceKey); it.remove(manualGapsKey)
            }
            it.remove(pointCacheKey)
            if(online!=null) check(online.current()) { "在线配置或账号状态已变化，原数据已保留" }
        }
    }

    private fun Preferences.readRoadReport(config: DiagnosticConfig?): RoadBatchReport? {
        if (config == null || get(capturedKey) != true || get(captureRevisionKey) != CURRENT_CAPTURE_REVISION) return null
        val report = get(roadReportKey)?.let { runCatching { RoadBatchCodec.decode(it) }.getOrNull() } ?: return null
        val plan = runCatching { RoadBatchPlan.create(config, epoch()) }.getOrNull() ?: return null
        val allowed = plan.requests.map { it.fromId to it.toId }.toSet()
        if (report.scope != plan.scope || report.candidateCount != plan.candidateCount ||
            report.links.any { (it.fromId to it.toId) !in allowed }) return null
        return if (report.phase == RoadBatchPhase.RUNNING) report.copy(phase = RoadBatchPhase.INTERRUPTED) else report
    }

    private fun Preferences.readRoadEvidence(config: DiagnosticConfig?, report: RoadBatchReport?): RoadEvidenceSnapshot? {
        if (config == null || report == null || report.reviewRevision != RoadBatchReport.CURRENT_REVIEW_REVISION) return null
        val evidence = get(roadEvidenceKey)?.let { runCatching { RoadEvidenceCodec.decode(it) }.getOrNull() } ?: return null
        if (evidence.scope != report.scope) return null
        val allowed = report.links.filter { link -> link.failure == null && link.issues.all {
            it in setOf(RoadGeometryIssue.DISCONNECTED_STEPS, RoadGeometryIssue.DISTANCE_INCONSISTENT)
        } }.map { it.fromId to it.toId }.toSet()
        if (evidence.legs.any { (it.fromId to it.toId) !in allowed }) return null
        val candidates = io.github.ibudaoa.domain.ConfigurationReviewer.review(config).candidates?.map { it.id }?.toSet() ?: return null
        if (evidence.legs.any { it.fromId !in candidates || it.toId !in candidates }) return null
        return evidence
    }

    companion object { const val CURRENT_CAPTURE_REVISION = 1 }

    private fun Preferences.readGapEvidence(roads: RoadEvidenceSnapshot?): RoadGapEvidence? {
        if (roads == null) return null
        val saved = get(gapEvidenceKey)?.let { runCatching { RoadGapEvidenceCodec.decode(it) }.getOrNull() } ?: return null
        if (saved.scope != roads.scope || saved.evidenceFingerprint != runCatching { RoadGapPlan.fingerprint(roads) }.getOrNull()) return null
        val allowed = roads.legs.flatMap { record -> record.leg.steps.zipWithNext().map { (a, b) -> RoadGap(a.last(), b.first()) } }.toSet()
        return saved.takeIf { it.records.all { record -> record.gap in allowed } }
    }

    private fun Preferences.readManualGaps(roads: RoadEvidenceSnapshot?): ManualRoadGaps? {
        if (roads == null) return null
        val saved = get(manualGapsKey)?.let { runCatching { ManualRoadGapsCodec.decode(it) }.getOrNull() } ?: return null
        return saved.takeIf { it.evidenceFingerprint == runCatching { RoadGapPlan.fingerprint(roads) }.getOrNull() }
    }

    suspend fun setManualGap(expected: RoadGapPlan, index: Int, confirmed: Boolean) {
        store.edit { preferences ->
            requireUnlocked(preferences)
            check(preferences[reviewedDirectionsKey] == null) { "请先撤销已确认的往返道路" }
            val config = preferences[configKey]?.let(DiagnosticCodec::decode) ?: error("学校配置已变化")
            val report = preferences.readRoadReport(config)
            val roads = preferences.readRoadEvidence(config, report) ?: error("道路资料已变化")
            val plan = RoadGapPlan.create(config, roads, io.github.ibudaoa.maps.AmapDistance())
            check(plan.scope == expected.scope && plan.evidenceFingerprint == expected.evidenceFingerprint &&
                plan.gaps == expected.gaps && index in plan.gaps.indices) { "道路资料已变化，请重新检查" }
            val old = preferences.readManualGaps(roads)?.indices.orEmpty()
            val next = if (confirmed) old + index else old - index
            if (next.isEmpty()) preferences.remove(manualGapsKey)
            else preferences[manualGapsKey] = ManualRoadGapsCodec.encode(ManualRoadGaps(plan.evidenceFingerprint, next))
        }
    }

    private fun Preferences.readDirections(roads: RoadEvidenceSnapshot?): ReviewedRoadDirections? {
        if (roads == null) return null
        val saved = get(reviewedDirectionsKey)?.let { runCatching { ReviewedRoadDirectionsCodec.decode(it) }.getOrNull() } ?: return null
        val manual = readManualGaps(roads)
        return saved.takeIf { it.scope == roads.scope && it.evidenceFingerprint == runCatching { RoadGapPlan.fingerprint(roads) }.getOrNull() &&
            it.manualGapsSnapshot == manual?.let(ManualRoadGapsCodec::encode) }
    }

    suspend fun setReviewedDirection(expected: RoadScope, fingerprint: String, pair: Pair<String, String>, confirmed: Boolean) {
        store.edit { preferences ->
            requireUnlocked(preferences)
            val config = preferences[configKey]?.let(DiagnosticCodec::decode) ?: error("学校配置已变化")
            val roads = preferences.readRoadEvidence(config, preferences.readRoadReport(config)) ?: error("道路资料已变化")
            check(roads.scope == expected && RoadGapPlan.fingerprint(roads) == fingerprint)
            val manual = preferences.readManualGaps(roads)
            val graph = ObservedRoadGraphBuilder.build(config, roads, io.github.ibudaoa.maps.AmapDistance(), preferences.readGapEvidence(roads), manual)
            check(graph.edges.any { (it.fromId to it.toId) == pair })
            val previous = preferences.readDirections(roads)
            check(preferences[reviewedDirectionsKey] == null || previous != null)
            val old = previous?.reversible.orEmpty()
            val next = if (confirmed) old + pair else old - pair
            if (next.isEmpty()) preferences.remove(reviewedDirectionsKey)
            else {
                val review = ReviewedRoadDirections(roads.scope, fingerprint, manual?.let(ManualRoadGapsCodec::encode), next)
                ReviewedRoadDirectionsBuilder.apply(graph, review, manual)
                preferences[reviewedDirectionsKey] = ReviewedRoadDirectionsCodec.encode(review)
            }
        }
    }

    /** One explicit review of the current complete snapshot; never inferred from a diagnostic import. */
    suspend fun confirmAllRoadConnectionsAndDirections(expected: RoadGapPlan) {
        store.edit { preferences ->
            requireUnlocked(preferences)
            val config = preferences[configKey]?.let(DiagnosticCodec::decode) ?: error("学校配置已变化")
            val roads = preferences.readRoadEvidence(config, preferences.readRoadReport(config)) ?: error("道路资料已变化")
            val metric = io.github.ibudaoa.maps.AmapDistance()
            val plan = RoadGapPlan.create(config, roads, metric)
            check(plan == expected && plan.gaps.size == plan.eligibleGapCount && plan.gaps.isNotEmpty()) {
                "道路资料已变化或未完整展示，原有确认已保留"
            }
            val manual = ManualRoadGaps(plan.evidenceFingerprint, plan.gaps.indices.toSet())
            val graph = ObservedRoadGraphBuilder.build(config, roads, metric, preferences.readGapEvidence(roads), manual)
            val pairs = graph.edges.map { it.fromId to it.toId }.toSet()
            check(pairs.isNotEmpty() && graph.unjoinedLegs.isEmpty()) { "仍有道路无法接通，原有确认已保留" }
            val directions = ReviewedRoadDirections(roads.scope, plan.evidenceFingerprint,
                ManualRoadGapsCodec.encode(manual), pairs)
            ReviewedRoadDirectionsBuilder.apply(graph, directions, manual)
            preferences[manualGapsKey] = ManualRoadGapsCodec.encode(manual)
            preferences[reviewedDirectionsKey] = ReviewedRoadDirectionsCodec.encode(directions)
        }
    }

    suspend fun setAllReviewedDirections(expected: RoadScope, fingerprint: String) {
        store.edit { preferences ->
            requireUnlocked(preferences)
            val config = preferences[configKey]?.let(DiagnosticCodec::decode) ?: error("学校配置已变化")
            val roads = preferences.readRoadEvidence(config, preferences.readRoadReport(config)) ?: error("道路资料已变化")
            check(roads.scope == expected && RoadGapPlan.fingerprint(roads) == fingerprint)
            val manual = preferences.readManualGaps(roads)
            val graph = ObservedRoadGraphBuilder.build(config, roads, io.github.ibudaoa.maps.AmapDistance(), preferences.readGapEvidence(roads), manual)
            val pairs = graph.edges.map { it.fromId to it.toId }.toSet()
            check(pairs.isNotEmpty())
            val existing = preferences.readDirections(roads)
            check(preferences[reviewedDirectionsKey] == null || existing != null)
            val review = ReviewedRoadDirections(roads.scope, fingerprint, manual?.let(ManualRoadGapsCodec::encode), pairs)
            ReviewedRoadDirectionsBuilder.apply(graph, review, manual)
            preferences[reviewedDirectionsKey] = ReviewedRoadDirectionsCodec.encode(review)
        }
    }

    suspend fun saveGapEvidence(evidence: RoadGapEvidence) {
        val encoded = RoadGapEvidenceCodec.encode(evidence)
        store.edit { preferences ->
            val config = preferences[configKey]?.let(DiagnosticCodec::decode) ?: error("Missing configuration")
            val report = preferences.readRoadReport(config)
            val roads = preferences.readRoadEvidence(config, report) ?: error("Missing road evidence")
            check(roads.scope == evidence.scope && RoadGapPlan.fingerprint(roads) == evidence.evidenceFingerprint)
            preferences.readGapEvidence(roads)?.let { existing ->
                check(evidence.records.take(existing.records.size) == existing.records)
            }
            val allowed = roads.legs.flatMap { it.leg.steps.zipWithNext().map { (a, b) -> RoadGap(a.last(), b.first()) } }.toSet()
            check(evidence.records.all { it.gap in allowed })
            preferences[gapEvidenceKey] = encoded
        }
    }

    suspend fun saveRoadReport(report: RoadBatchReport) = saveRoadCheck(report, null)

    /** Atomically replace both metadata and campus geometry; null clears any older geometry. */
    suspend fun saveRoadCheck(report: RoadBatchReport, evidence: RoadEvidenceSnapshot?) {
        require(report.phase != RoadBatchPhase.RUNNING && report.phase != RoadBatchPhase.IDLE)
        val encoded = RoadBatchCodec.text(report)
        val evidenceEncoded = evidence?.let(RoadEvidenceCodec::encode)
        store.edit { preferences ->
            check(preferences[capturedKey] == true && preferences[captureRevisionKey] == CURRENT_CAPTURE_REVISION &&
                preferences.epoch() == report.scope.connectionEpoch)
            val config = preferences[configKey]?.let(DiagnosticCodec::decode) ?: error("Missing road context")
            val plan = RoadBatchPlan.create(config, preferences.epoch())
            check(plan.scope == report.scope && plan.candidateCount == report.candidateCount)
            val allowed = plan.requests.map { it.fromId to it.toId }.toSet()
            check(report.links.all { (it.fromId to it.toId) in allowed })
            check(preferences[manualGapsKey] == null && preferences[reviewedDirectionsKey] == null) {
                "已确认道路需要保留，不能直接覆盖"
            }
            preferences.remove(gapEvidenceKey)
            preferences.remove(manualGapsKey)
            if (evidence != null) {
                check(evidence.scope == plan.scope && report.reviewRevision == RoadBatchReport.CURRENT_REVIEW_REVISION)
                val good = report.links.filter { it.failure == null && it.issues.all { issue ->
                    issue in setOf(RoadGeometryIssue.DISCONNECTED_STEPS, RoadGeometryIssue.DISTANCE_INCONSISTENT)
                } }.map { it.fromId to it.toId }.toSet()
                check(evidence.legs.all { (it.fromId to it.toId) in allowed && (it.fromId to it.toId) in good })
                preferences[roadEvidenceKey] = evidenceEncoded!!
            } else preferences.remove(roadEvidenceKey)
            preferences[roadReportKey] = encoded
        }
    }

    suspend fun acknowledgePreparation() { store.edit { it[preparationKey] = true } }

    suspend fun savePointCache(cache: PointSelectionCache) {
        val encoded = PointCacheCodec.encode(cache)
        store.edit { preferences ->
            check(preferences[capturedKey] == true && preferences.epoch() == cache.scope.connectionEpoch) {
                "准备配置已改变，点集未保存"
            }
            val config = preferences[configKey]?.let(DiagnosticCodec::decode) ?: error("学校配置尚未就绪")
            fun id(key: String) = (config.config[key] as? JsonPrimitive)?.longOrNull
            check(id("school_id") == cache.scope.schoolId && id("term_id") == cache.scope.termId &&
                id("run_zone_id") == cache.scope.zoneId) { "点集与学校配置不一致" }
            val points = (config.config["run_line_info"] as? JsonObject)?.get("point_list") as? JsonArray
            val ids = points.orEmpty().mapNotNull { point ->
                val item = point as? JsonObject
                ((item?.get("id") ?: item?.get("point_id")) as? JsonPrimitive)?.content
            }.toSet()
            check(cache.selectedIds.all { it in ids }) { "点集不属于当前候选表" }
            val previous = preferences[pointCacheKey]?.let(PointCacheCodec::decode)
            if (previous?.locked == true || cache.locked) {
                check(previous != null && previous.scope == cache.scope && previous.selectedOn == cache.selectedOn &&
                    previous.origin == cache.origin && previous.selectedIds == cache.selectedIds &&
                    (!previous.locked || cache.locked) && cache.confirmedIds.containsAll(previous.confirmedIds)) {
                    "锁定点集不可替换或回退"
                }
                if (previous.locked) check(previous.executionLockId == cache.executionLockId) { "本次锁定标识不一致" }
            }
            preferences[epochKey] = cache.scope.connectionEpoch
            preferences[pointCacheKey] = encoded
        }
    }

    suspend fun clear() { store.edit {
        requireUnlocked(it)
        it[epochKey] = nextEpoch(it.epoch())
        it.remove(configKey); it.remove(capturedKey); it.remove(pointCacheKey)
        it.remove(captureRevisionKey); it.remove(capturedAtKey); it.remove(roadReportKey); it.remove(roadEvidenceKey); it.remove(gapEvidenceKey); it.remove(manualGapsKey); it.remove(reviewedDirectionsKey)
    } }

    /** Session controller may call only after querying/confirming the platform task is resolved. */
    suspend fun releaseResolvedPointCache(expected: PointSelectionCache) {
        check(expected.locked) { "需要已锁定点集的核对结果" }
        val encoded = PointCacheCodec.encode(expected)
        store.edit {
            check(it[pointCacheKey] == encoded) { "点集状态已变化，需要重新核对" }
            // Preserve the assigned set for preparation reuse; completion belongs to the resolved record.
            it[pointCacheKey] = PointCacheCodec.encode(expected.copy(locked = false,
                confirmedIds = emptySet(), executionLockId = null))
        }
    }

    private fun requireUnlocked(preferences: Preferences) {
        val previous = preferences[pointCacheKey]?.let(PointCacheCodec::decode)
        check(previous?.locked != true) { "任务点集已锁定，请先核对平台状态" }
    }

    private fun Preferences.epoch(): Long = get(epochKey) ?: if (get(configKey) != null) 1L else 0L
    private fun nextEpoch(value: Long): Long {
        check(value in 0 until Long.MAX_VALUE) { "本地连接状态需要重置" }
        return value + 1
    }
}

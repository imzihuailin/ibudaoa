package io.github.ibudaoa.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ibudaoa.diagnostics.DiagnosticCodec
import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.domain.PlatformOrigin
import io.github.ibudaoa.domain.PointScope
import io.github.ibudaoa.domain.PointSelectionCache
import java.time.LocalDate
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.ibudaoa.roads.*

class DiagnosticStoreTest {
    @Test fun `complete preparation persists and clear requires first setup again`() = runBlocking {
        val backend = MemoryStore(); val store = DiagnosticStore(backend)
        store.save(RoadFixture.config(), captured = true)
        store.setMapConsent(true)
        store.acknowledgePreparation()
        store.migratePreparationCompletion()
        assertFalse(store.pointPlanningStorage.first().preparationCompleted)
        val snapshot = store.pointPlanningStorage.first()
        val plan = RoadBatchPlan.create(snapshot.config!!, snapshot.connectionEpoch)
        val report = RoadBatchReport(plan.scope, RoadBatchPhase.COMPLETE, plan.candidateCount,
            plan.requests.size, plan.requests.map { RoadLinkSummary(it.fromId, it.toId) })
        val evidence = RoadEvidenceSnapshot(plan.scope, plan.requests.map {
            CandidateRoadEvidence(it.fromId, it.toId, WalkingLeg(100.0, listOf(listOf(it.from, it.to))))
        })
        store.saveRoadCheck(report, evidence)
        assertTrue(DiagnosticStore(backend).pointPlanningStorage.first().preparationCompleted)
        store.clear()
        assertFalse(DiagnosticStore(backend).pointPlanningStorage.first().preparationCompleted)
    }

    @Test fun `upgrade migrates complete old evidence but not summary alone`() = runBlocking {
        val backend = MemoryStore(); val store = DiagnosticStore(backend)
        store.save(RoadFixture.config(), captured = true); store.setMapConsent(true)
        val snapshot = store.pointPlanningStorage.first()
        val plan = RoadBatchPlan.create(snapshot.config!!, snapshot.connectionEpoch)
        val report = RoadBatchReport(plan.scope, RoadBatchPhase.COMPLETE, plan.candidateCount,
            plan.requests.size, plan.requests.map { RoadLinkSummary(it.fromId, it.toId) })
        val evidence = RoadEvidenceSnapshot(plan.scope, plan.requests.map {
            CandidateRoadEvidence(it.fromId, it.toId, WalkingLeg(100.0, listOf(listOf(it.from, it.to))))
        })
        val completedKey = androidx.datastore.preferences.core.booleanPreferencesKey("initial_preparation_completed_v1")
        store.saveRoadCheck(report, evidence)
        backend.edit { it.remove(completedKey) } // Previous release has no completion flag.
        val upgraded = DiagnosticStore(backend)
        upgraded.migratePreparationCompletion()
        assertTrue(upgraded.pointPlanningStorage.first().preparationCompleted)
        backend.edit { it.remove(completedKey) }
        store.saveRoadReport(report)
        upgraded.migratePreparationCompletion()
        assertFalse(upgraded.pointPlanningStorage.first().preparationCompleted)
    }

    @Test fun `map consent defaults off persists separately and can be revoked`() = runBlocking {
        val backend = MemoryStore()
        val store = DiagnosticStore(backend)
        assertFalse(store.mapConsent.first())
        store.setMapConsent(true)
        store.save(config(), captured = true)
        assertTrue(DiagnosticStore(backend).mapConsent.first())
        assertFalse(DiagnosticCodec.encode(store.config.first()!!).contains("amap_privacy"))
        store.setMapConsent(false)
        assertFalse(DiagnosticStore(backend).mapConsent.first())
        assertTrue(store.pointPlanningStorage.first().captured)
    }

    @Test fun `fresh official capture marks projection revision but import does not`() = runBlocking {
        val store = DiagnosticStore(MemoryStore())
        assertEquals(0, store.pointPlanningStorage.first().captureRevision)
        store.save(config(), captured = true)
        assertEquals(DiagnosticStore.CURRENT_CAPTURE_REVISION, store.pointPlanningStorage.first().captureRevision)
        store.save(config(), captured = false)
        assertEquals(0, store.pointPlanningStorage.first().captureRevision)
    }

    @Test fun `road reports survive repository recreation and reject old capture scopes`() = runBlocking {
        val backend = MemoryStore()
        val store = DiagnosticStore(backend)
        val config = io.github.ibudaoa.roads.RoadFixture.config()
        store.save(config, captured = true)
        val plan = io.github.ibudaoa.roads.RoadBatchPlan.create(config, store.pointPlanningStorage.first().connectionEpoch)
        val report = io.github.ibudaoa.roads.RoadBatchReport(plan.scope,
            io.github.ibudaoa.roads.RoadBatchPhase.INTERRUPTED, plan.candidateCount, plan.requests.size)
        store.saveRoadReport(report)
        assertEquals(report, DiagnosticStore(backend).pointPlanningStorage.first().roadReport)
        store.save(io.github.ibudaoa.roads.RoadFixture.config(zone = 4), captured = true)
        assertNull(store.pointPlanningStorage.first().roadReport)
        assertTrue(runCatching { store.saveRoadReport(report) }.isFailure)
        store.clear()
        assertNull(store.pointPlanningStorage.first().roadReport)
        assertEquals(0, store.pointPlanningStorage.first().captureRevision)
    }

    @Test fun `reopening quarantines a wrong-scope report even when its JSON is structurally valid`() = runBlocking {
        val backend = MemoryStore(); val store = DiagnosticStore(backend)
        val config = io.github.ibudaoa.roads.RoadFixture.config()
        store.save(config, captured = true)
        val plan = io.github.ibudaoa.roads.RoadBatchPlan.create(config, store.pointPlanningStorage.first().connectionEpoch)
        val report = io.github.ibudaoa.roads.RoadBatchReport(plan.scope.copy(zoneId = 999),
            io.github.ibudaoa.roads.RoadBatchPhase.INTERRUPTED, plan.candidateCount, plan.requests.size)
        backend.edit { it[stringPreferencesKey("candidate_road_check")] = io.github.ibudaoa.roads.RoadBatchCodec.text(report) }
        assertNull(DiagnosticStore(backend).pointPlanningStorage.first().roadReport)
    }

    @Test fun `an unfinished persisted attempt restores as interrupted and cannot auto resume`() = runBlocking {
        val backend = MemoryStore(); val store = DiagnosticStore(backend)
        val config = io.github.ibudaoa.roads.RoadFixture.config()
        store.save(config, captured = true)
        val plan = io.github.ibudaoa.roads.RoadBatchPlan.create(config, store.pointPlanningStorage.first().connectionEpoch)
        val report = io.github.ibudaoa.roads.RoadBatchReport(plan.scope,
            io.github.ibudaoa.roads.RoadBatchPhase.RUNNING, plan.candidateCount, plan.requests.size)
        backend.edit { it[stringPreferencesKey("candidate_road_check")] = io.github.ibudaoa.roads.RoadBatchCodec.text(report) }
        assertEquals(io.github.ibudaoa.roads.RoadBatchPhase.INTERRUPTED,
            DiagnosticStore(backend).pointPlanningStorage.first().roadReport!!.phase)
        assertTrue(store.pointPlanningStorage.first().capturedAtEpochSecond > 0)
    }

    @Test fun `private road evidence is atomic with its report and cleared on reconfiguration`() = runBlocking {
        val backend = MemoryStore(); val store = DiagnosticStore(backend)
        val config = io.github.ibudaoa.roads.RoadFixture.config()
        store.save(config, captured = true)
        val plan = io.github.ibudaoa.roads.RoadBatchPlan.create(config, store.pointPlanningStorage.first().connectionEpoch)
        val report = io.github.ibudaoa.roads.RoadBatchReport(plan.scope, io.github.ibudaoa.roads.RoadBatchPhase.INTERRUPTED,
            plan.candidateCount, plan.requests.size, listOf(io.github.ibudaoa.roads.RoadLinkSummary("a", "b")))
        val evidence = io.github.ibudaoa.roads.RoadEvidenceSnapshot(plan.scope, listOf(
            io.github.ibudaoa.roads.CandidateRoadEvidence("a", "b", io.github.ibudaoa.roads.RoadFixture.leg())))
        store.saveRoadCheck(report, evidence)
        val reopened = DiagnosticStore(backend).pointPlanningStorage.first()
        assertEquals(report, reopened.roadReport); assertEquals(evidence, reopened.roadEvidence)
        store.save(io.github.ibudaoa.roads.RoadFixture.config(zone = 4), captured = true)
        assertNull(store.pointPlanningStorage.first().roadEvidence)
        assertTrue(runCatching { store.saveRoadCheck(report, evidence) }.isFailure)
    }

    @Test fun `road evidence cannot be stored for a failed or unrelated query`() = runBlocking {
        val store = DiagnosticStore(MemoryStore()); val config = io.github.ibudaoa.roads.RoadFixture.config()
        store.save(config, captured = true)
        val plan = io.github.ibudaoa.roads.RoadBatchPlan.create(config, store.pointPlanningStorage.first().connectionEpoch)
        val report = io.github.ibudaoa.roads.RoadBatchReport(plan.scope, io.github.ibudaoa.roads.RoadBatchPhase.INTERRUPTED,
            plan.candidateCount, plan.requests.size, listOf(io.github.ibudaoa.roads.RoadLinkSummary("a", "b",
                io.github.ibudaoa.roads.RoadLinkFailure.SDK_ERROR)))
        val evidence = io.github.ibudaoa.roads.RoadEvidenceSnapshot(plan.scope, listOf(
            io.github.ibudaoa.roads.CandidateRoadEvidence("a", "b", io.github.ibudaoa.roads.RoadFixture.leg())))
        assertTrue(runCatching { store.saveRoadCheck(report, evidence) }.isFailure)
        assertNull(store.pointPlanningStorage.first().roadEvidence)
    }


    @Test fun `manual gap confirmation persists can be revoked and cannot outlive road scope`() = runBlocking {
        val backend = MemoryStore(); val store = DiagnosticStore(backend)
        val config = RoadFixture.config(); store.save(config, captured = true)
        val batch = RoadBatchPlan.create(config, store.pointPlanningStorage.first().connectionEpoch)
        val report = RoadBatchReport(batch.scope, RoadBatchPhase.INTERRUPTED, batch.candidateCount, batch.requests.size,
            listOf(RoadLinkSummary("a", "b", issues = setOf(RoadGeometryIssue.DISCONNECTED_STEPS))))
        val before = CampusCoordinate(30.001, 114.0); val after = CampusCoordinate(30.001006, 114.0)
        val evidence = RoadEvidenceSnapshot(batch.scope, listOf(CandidateRoadEvidence("a", "b",
            WalkingLeg(222.39, listOf(listOf(RoadFixture.a, before), listOf(after, RoadFixture.c))))))
        store.saveRoadCheck(report, evidence)
        val plan = RoadGapPlan.create(config, evidence, io.github.ibudaoa.maps.AmapDistance())
        assertEquals(1, plan.gaps.size)
        store.setManualGap(plan, 0, true)
        assertEquals(setOf(0), DiagnosticStore(backend).pointPlanningStorage.first().manualGaps!!.indices)
        store.setManualGap(plan, 0, false)
        assertNull(store.pointPlanningStorage.first().manualGaps)
        store.setManualGap(plan, 0, true)
        assertTrue(runCatching { store.saveRoadCheck(report, evidence) }.isFailure)
        assertTrue(runCatching { store.save(RoadFixture.config(zone = 4), captured = true) }.isFailure)
        assertEquals(setOf(0), store.pointPlanningStorage.first().manualGaps!!.indices)
        store.setManualGap(plan, 0, false)
        store.saveRoadCheck(report, evidence)
        assertNull(store.pointPlanningStorage.first().manualGaps)
        store.save(RoadFixture.config(zone = 4), captured = true)
        assertTrue(runCatching { store.setManualGap(plan, 0, true) }.isFailure)
    }

    @Test fun `damaged manual confirmation cannot be silently deleted by reconnect or import`() = runBlocking {
        val backend = MemoryStore(); val store = DiagnosticStore(backend)
        val original = RoadFixture.config(); store.save(original, captured = true)
        val key = stringPreferencesKey("private_manual_road_gaps_v1")
        backend.edit { it[key] = "bad-private-data" }
        assertTrue(runCatching { store.save(original, captured = true) }.isFailure)
        assertTrue(runCatching { store.save(RoadFixture.config(zone = 4), captured = true) }.isFailure)
        assertEquals("bad-private-data", backend.data.first()[key])
        assertEquals(original, store.pointPlanningStorage.first().config)
    }

    @Test fun `two way review persists rebases safely and blocks modification of its gap decisions`() = runBlocking {
        val backend = MemoryStore(); val store = DiagnosticStore(backend)
        val config = RoadFixture.config(); store.save(config, captured = true)
        val batch = RoadBatchPlan.create(config, store.pointPlanningStorage.first().connectionEpoch)
        val report = RoadBatchReport(batch.scope, RoadBatchPhase.INTERRUPTED, batch.candidateCount, batch.requests.size,
            listOf(RoadLinkSummary("a", "b", issues = setOf(RoadGeometryIssue.DISCONNECTED_STEPS))))
        val before = CampusCoordinate(30.001, 114.0); val after = CampusCoordinate(30.001006, 114.0)
        val evidence = RoadEvidenceSnapshot(batch.scope, listOf(CandidateRoadEvidence("a", "b",
            WalkingLeg(222.39, listOf(listOf(RoadFixture.a, before), listOf(after, RoadFixture.c))))))
        store.saveRoadCheck(report, evidence)
        val plan = RoadGapPlan.create(config, evidence, io.github.ibudaoa.maps.AmapDistance())
        store.setManualGap(plan, 0, true)
        store.setAllReviewedDirections(batch.scope, plan.evidenceFingerprint)
        val saved = DiagnosticStore(backend).pointPlanningStorage.first()
        assertEquals(setOf("a" to "b"), saved.reviewedDirections!!.reversible)
        assertTrue(runCatching { store.setManualGap(plan, 0, false) }.isFailure)
        assertTrue(runCatching { store.setReviewedDirection(batch.scope, plan.evidenceFingerprint, "b" to "a", true) }.isFailure)
        assertTrue(runCatching { store.save(RoadFixture.config(zone = 4), captured = true) }.isFailure)
        store.save(config, captured = true)
        val fresh = store.pointPlanningStorage.first()
        assertEquals(setOf(0), fresh.manualGaps!!.indices)
        assertEquals(setOf("a" to "b"), fresh.reviewedDirections!!.reversible)
        assertEquals(fresh.roadEvidence!!.scope, fresh.reviewedDirections!!.scope)
        assertTrue(runCatching { store.setReviewedDirection(batch.scope, plan.evidenceFingerprint, "a" to "b", false) }.isFailure)
        assertTrue(runCatching { store.setAllReviewedDirections(batch.scope, plan.evidenceFingerprint) }.isFailure)
        val graph = ObservedRoadGraphBuilder.build(config, fresh.roadEvidence!!, io.github.ibudaoa.maps.AmapDistance(), fresh.gapEvidence, fresh.manualGaps)
        assertEquals(2, ReviewedRoadDirectionsBuilder.apply(graph, fresh.reviewedDirections!!, fresh.manualGaps).edges.size)
        store.setReviewedDirection(graph.scope, graph.fingerprint, "a" to "b", false)
        assertNull(store.pointPlanningStorage.first().reviewedDirections)
        store.setManualGap(RoadGapPlan.create(config, fresh.roadEvidence!!, io.github.ibudaoa.maps.AmapDistance()), 0, false)
        assertNull(store.pointPlanningStorage.first().manualGaps)
    }

    private fun config(zone: Int = 3) = DiagnosticCodec.decode("""{"school_id":1,"term_id":2,"run_zone_id":$zone,
        "run_line_info":{"point_list":[{"id":"a"},{"id":"b"},{"id":"c"}]}}""")
    private fun cache(epoch: Long) = PointSelectionCache(PointScope(epoch, 1, 2, 3, "a".repeat(64)),
        LocalDate.of(2026, 10, 28), PlatformOrigin(CampusCoordinate(30.0, 114.0)), listOf("a", "b"))

    @Test fun `prepared cache survives repository recreation and stays tied to captured revision`() = runBlocking {
        val backend = MemoryStore(); val store = DiagnosticStore(backend)
        store.save(config(), captured = true)
        val snapshot = store.pointPlanningStorage.first()
        val cache = cache(snapshot.connectionEpoch)
        store.savePointCache(cache)
        assertEquals(cache, DiagnosticStore(backend).pointPlanningStorage.first().cache)
        assertTrue(snapshot.captured)
        assertEquals(3L, snapshot.config!!.zoneId)
    }

    @Test fun `new config replaces all context and rejects an old in-flight cache save`() = runBlocking {
        val store = DiagnosticStore(MemoryStore())
        store.save(config(), captured = true)
        val old = cache(store.pointPlanningStorage.first().connectionEpoch)
        store.savePointCache(old)
        store.save(config(zone = 4), captured = true)
        val latest = store.pointPlanningStorage.first()
        assertTrue(latest.connectionEpoch > old.scope.connectionEpoch)
        assertEquals(4L, latest.config!!.zoneId)
        assertNull(latest.cache)
        assertTrue(runCatching { store.savePointCache(old) }.isFailure)
        assertEquals(latest, store.pointPlanningStorage.first())
    }

    @Test fun `import cannot reuse official capture cache or be used as captured session`() = runBlocking {
        val store = DiagnosticStore(MemoryStore()); store.save(config(), captured = true)
        store.savePointCache(cache(store.pointPlanningStorage.first().connectionEpoch))
        store.save(config())
        val imported = store.pointPlanningStorage.first()
        assertFalse(imported.captured); assertNull(imported.cache)
        assertTrue(runCatching { store.savePointCache(cache(imported.connectionEpoch)) }.isFailure)
    }

    @Test fun `account reconnect preserves locked task configuration and selected points`() = runBlocking {
        val store=DiagnosticStore(MemoryStore());store.save(config(),captured=true)
        val before=store.pointPlanningStorage.first()
        val draft=cache(before.connectionEpoch)
        store.savePointCache(draft)
        val locked=draft.lockForExecution()
        store.savePointCache(locked)
        assertFalse(store.saveCapturedAccountSchool(config(zone=4)))
        val after=store.pointPlanningStorage.first()
        assertEquals(before.config,after.config)
        assertEquals(before.connectionEpoch,after.connectionEpoch)
        assertEquals(locked,after.cache)
        store.releaseResolvedPointCache(locked)
        assertTrue(store.saveCapturedAccountSchool(config(zone=4)))
        assertEquals(config(zone=4),store.pointPlanningStorage.first().config)
    }

    @Test fun `locked points cannot be overwritten cleared or discarded by reconnect`() = runBlocking {
        val store = DiagnosticStore(MemoryStore()); store.save(config(), captured = true)
        val draft = cache(store.pointPlanningStorage.first().connectionEpoch)
        store.savePointCache(draft); val locked = draft.lockForExecution(); store.savePointCache(locked)
        assertTrue(runCatching { store.save(config(zone = 4), captured = true) }.isFailure)
        assertTrue(runCatching { store.clear() }.isFailure)
        assertTrue(runCatching { store.savePointCache(draft) }.isFailure)
        assertTrue(runCatching { store.savePointCache(locked.copy(selectedIds = listOf("b", "c"))) }.isFailure)
        assertEquals(locked, store.pointPlanningStorage.first().cache)
    }

    @Test fun `confirmed progress is monotonic and stale reconciliation cannot clear newer state`() = runBlocking {
        val store = DiagnosticStore(MemoryStore()); store.save(config(), captured = true)
        val draft = cache(store.pointPlanningStorage.first().connectionEpoch)
        store.savePointCache(draft); val locked = draft.lockForExecution(); store.savePointCache(locked)
        val progressed = locked.withPlatformConfirmedPoint("a"); store.savePointCache(progressed)
        assertTrue(runCatching { store.savePointCache(locked) }.isFailure)
        assertTrue(runCatching { store.releaseResolvedPointCache(locked) }.isFailure)
        assertEquals(progressed, store.pointPlanningStorage.first().cache)
        store.releaseResolvedPointCache(progressed)
        val released = store.pointPlanningStorage.first().cache!!
        assertEquals(progressed.selectedIds, released.selectedIds)
        assertFalse(released.locked); assertTrue(released.confirmedIds.isEmpty())
        store.clear(); val cleared = store.pointPlanningStorage.first()
        assertNull(cleared.config); assertTrue(cleared.connectionEpoch > draft.scope.connectionEpoch)
    }

    @Test fun `old task callback cannot unlock a new task with identical selected points`() = runBlocking {
        val store = DiagnosticStore(MemoryStore()); store.save(config(), captured = true)
        val draft = cache(store.pointPlanningStorage.first().connectionEpoch)
        store.savePointCache(draft)
        val first = draft.lockForExecution(); store.savePointCache(first)
        store.releaseResolvedPointCache(first)
        val second = store.pointPlanningStorage.first().cache!!.lockForExecution()
        store.savePointCache(second)
        assertTrue(first.executionLockId != second.executionLockId)
        assertTrue(runCatching { store.releaseResolvedPointCache(first) }.isFailure)
        assertEquals(second, store.pointPlanningStorage.first().cache)
    }

    @Test fun `cache must belong to current candidate table and must be prepared before locking`() = runBlocking {
        val store = DiagnosticStore(MemoryStore()); store.save(config(), captured = true)
        val draft = cache(store.pointPlanningStorage.first().connectionEpoch)
        assertTrue(runCatching { store.savePointCache(draft.lockForExecution()) }.isFailure)
        assertTrue(runCatching { store.savePointCache(draft.copy(selectedIds = listOf("a", "foreign"))) }.isFailure)
        assertNull(store.pointPlanningStorage.first().cache)
    }

    @Test fun `real preferences file restores a locked selection after storage restart`() = runBlocking {
        val directory = Files.createTempDirectory("point-cache-test").toFile()
        val firstJob = SupervisorJob(); val secondJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + Dispatchers.IO)
        val secondScope = CoroutineScope(secondJob + Dispatchers.IO)
        try {
            val first = DiagnosticStore(PreferenceDataStoreFactory.create(scope = firstScope) {
                File(directory, "selection.preferences_pb")
            })
            first.save(config(), captured = true)
            val draft = cache(first.pointPlanningStorage.first().connectionEpoch)
            first.savePointCache(draft)
            val locked = draft.lockForExecution().withPlatformConfirmedPoint("a")
            first.savePointCache(locked)
            firstScope.cancel(); firstJob.join()
            val reopened = DiagnosticStore(PreferenceDataStoreFactory.create(scope = secondScope) {
                File(directory, "selection.preferences_pb")
            })
            val restored = reopened.pointPlanningStorage.first()
            assertEquals(locked, restored.cache)
            assertEquals(locked.scope.connectionEpoch, restored.connectionEpoch)
            assertTrue(restored.captured)
        } finally {
            firstScope.cancel(); secondScope.cancel(); firstJob.join(); secondJob.join()
            directory.deleteRecursively()
        }
    }

    @Test fun `candidate road geometry survives a real private preferences file reopen`() = runBlocking {
        val directory = Files.createTempDirectory("candidate-road-evidence").toFile()
        val firstJob = SupervisorJob(); val secondJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + Dispatchers.IO); val secondScope = CoroutineScope(secondJob + Dispatchers.IO)
        try {
            val first = DiagnosticStore(PreferenceDataStoreFactory.create(scope = firstScope) { File(directory, "roads.preferences_pb") })
            val config = io.github.ibudaoa.roads.RoadFixture.config()
            first.save(config, captured = true)
            val plan = io.github.ibudaoa.roads.RoadBatchPlan.create(config, first.pointPlanningStorage.first().connectionEpoch)
            val report = io.github.ibudaoa.roads.RoadBatchReport(plan.scope, io.github.ibudaoa.roads.RoadBatchPhase.INTERRUPTED,
                plan.candidateCount, plan.requests.size, listOf(io.github.ibudaoa.roads.RoadLinkSummary("a", "b")))
            val evidence = io.github.ibudaoa.roads.RoadEvidenceSnapshot(plan.scope, listOf(
                io.github.ibudaoa.roads.CandidateRoadEvidence("a", "b", io.github.ibudaoa.roads.RoadFixture.leg())))
            first.saveRoadCheck(report, evidence)
            firstScope.cancel(); firstJob.join()
            val second = DiagnosticStore(PreferenceDataStoreFactory.create(scope = secondScope) { File(directory, "roads.preferences_pb") })
            assertEquals(evidence, second.pointPlanningStorage.first().roadEvidence)
            assertEquals(report, second.pointPlanningStorage.first().roadReport)
        } finally {
            firstScope.cancel(); secondScope.cancel(); firstJob.join(); secondJob.join(); directory.deleteRecursively()
        }
    }

    @Test fun `verified gap evidence persists and is cleared with its road context`() = runBlocking {
        val directory = Files.createTempDirectory("gap-evidence").toFile()
        val firstJob = SupervisorJob(); val secondJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + Dispatchers.IO); val secondScope = CoroutineScope(secondJob + Dispatchers.IO)
        try {
            val first = DiagnosticStore(PreferenceDataStoreFactory.create(scope = firstScope) { File(directory, "gaps.preferences_pb") })
            val config = RoadFixture.config(); first.save(config, captured = true)
            val plan = RoadBatchPlan.create(config, first.pointPlanningStorage.first().connectionEpoch)
            val before = CampusCoordinate(30.001, 114.0); val after = CampusCoordinate(30.001006, 114.0)
            val evidence = RoadEvidenceSnapshot(plan.scope, listOf(CandidateRoadEvidence("a", "b", WalkingLeg(222.39,
                listOf(listOf(RoadFixture.a, before), listOf(after, RoadFixture.c))))))
            val report = RoadBatchReport(plan.scope, RoadBatchPhase.INTERRUPTED, plan.candidateCount, plan.requests.size,
                listOf(RoadLinkSummary("a", "b", issues = setOf(RoadGeometryIssue.DISCONNECTED_STEPS))))
            first.saveRoadCheck(report, evidence)
            val gap = RoadGap(before, after)
            val saved = RoadGapEvidence(plan.scope, RoadGapPlan.fingerprint(evidence), listOf(
                RoadGapRecord(gap, GapCheckOutcome.EXACT_CONNECTED, WalkingLeg(0.67, listOf(listOf(before, after))))))
            first.saveGapEvidence(saved)
            val manualPlan = RoadGapPlan.create(config, evidence, io.github.ibudaoa.maps.AmapDistance())
            first.setManualGap(manualPlan, 0, true)
            first.setReviewedDirection(plan.scope, manualPlan.evidenceFingerprint, "a" to "b", true)
            first.savePointCache(cache(plan.scope.connectionEpoch))
            assertTrue(runCatching { first.saveGapEvidence(saved.copy(records = emptyList())) }.isFailure)
            firstScope.cancel(); firstJob.join()
            val second = DiagnosticStore(PreferenceDataStoreFactory.create(scope = secondScope) { File(directory, "gaps.preferences_pb") })
            assertEquals(saved, second.pointPlanningStorage.first().gapEvidence)
            assertEquals(setOf(0), second.pointPlanningStorage.first().manualGaps!!.indices)
            assertEquals(setOf("a" to "b"), second.pointPlanningStorage.first().reviewedDirections!!.reversible)
            second.save(config, captured = true)
            val rebased = second.pointPlanningStorage.first()
            val rebasedRoads = rebased.roadEvidence!!
            assertTrue(rebased.connectionEpoch > plan.scope.connectionEpoch)
            assertEquals(plan.scope.copy(connectionEpoch = rebased.connectionEpoch), rebasedRoads.scope)
            assertEquals(saved.records, rebased.gapEvidence!!.records)
            assertEquals(RoadGapPlan.fingerprint(rebasedRoads), rebased.gapEvidence!!.evidenceFingerprint)
            assertEquals(setOf(0), rebased.manualGaps!!.indices)
            assertEquals(RoadGapPlan.fingerprint(rebasedRoads), rebased.manualGaps!!.evidenceFingerprint)
            assertEquals(setOf("a" to "b"), rebased.reviewedDirections!!.reversible)
            assertEquals(rebasedRoads.scope, rebased.reviewedDirections!!.scope)
            assertTrue(runCatching { second.setManualGap(manualPlan, 0, false) }.isFailure)
            assertEquals(1, ObservedRoadGraphBuilder.build(config, rebasedRoads,
                io.github.ibudaoa.maps.AmapDistance(), rebased.gapEvidence).verifiedGapBridges)
            assertNull(rebased.cache) // Matching campus geometry does not prove the account or point set is unchanged.
            assertTrue(runCatching { second.saveGapEvidence(saved) }.isFailure)
            assertTrue(runCatching { second.save(RoadFixture.config(zone = 4), captured = true) }.isFailure)
            assertEquals(setOf(0), second.pointPlanningStorage.first().manualGaps!!.indices)
            val updatedPlan = RoadGapPlan.create(config, rebasedRoads, io.github.ibudaoa.maps.AmapDistance())
            second.setReviewedDirection(rebasedRoads.scope, updatedPlan.evidenceFingerprint, "a" to "b", false)
            second.setManualGap(updatedPlan, 0, false)
            second.save(RoadFixture.config(zone = 4), captured = true)
            assertNull(second.pointPlanningStorage.first().gapEvidence)
            assertNull(second.pointPlanningStorage.first().manualGaps)
        } finally {
            firstScope.cancel(); secondScope.cancel(); firstJob.join(); secondJob.join(); directory.deleteRecursively()
        }
    }

    @Test fun `corrupt config cannot retain captured readiness from a stale boolean`() = runBlocking {
        val backend = MemoryStore(); val store = DiagnosticStore(backend)
        store.save(config(), captured = true)
        backend.updateData { preferences -> preferences.toMutablePreferences().apply {
            this[stringPreferencesKey("sanitized_config")] = "CORRUPT_CONFIG_SENTINEL"
        } }
        val state = store.pointPlanningStorage.first()
        assertNull(state.config); assertFalse(state.captured)
    }

    @Test fun `private selection origin and local execution identity never enter config export`() = runBlocking {
        val store = DiagnosticStore(MemoryStore()); store.save(config(), captured = true)
        val draft = cache(store.pointPlanningStorage.first().connectionEpoch)
        store.savePointCache(draft); val locked = draft.lockForExecution(); store.savePointCache(locked)
        val exported = DiagnosticCodec.encode(store.pointPlanningStorage.first().config!!)
        assertFalse(exported.contains("origin_latitude"))
        assertFalse(exported.contains("execution_lock_id"))
        assertFalse(exported.contains(locked.executionLockId!!))
    }

    private suspend fun onlineConfirmedStore():Pair<DiagnosticStore,io.github.ibudaoa.diagnostics.DiagnosticConfig> {
        val store=DiagnosticStore(MemoryStore());val cfg=RoadFixture.config();store.save(cfg,captured=true)
        val batch=RoadBatchPlan.create(cfg,store.pointPlanningStorage.first().connectionEpoch)
        val before=CampusCoordinate(30.001,114.0);val after=CampusCoordinate(30.001006,114.0)
        val evidence=RoadEvidenceSnapshot(batch.scope,listOf(CandidateRoadEvidence("a","b",
            WalkingLeg(222.39,listOf(listOf(RoadFixture.a,before),listOf(after,RoadFixture.c))))))
        store.saveRoadCheck(RoadBatchReport(batch.scope,RoadBatchPhase.INTERRUPTED,batch.candidateCount,batch.requests.size,
            listOf(RoadLinkSummary("a","b",issues=setOf(RoadGeometryIssue.DISCONNECTED_STEPS)))),evidence)
        val plan=RoadGapPlan.create(cfg,evidence,io.github.ibudaoa.maps.AmapDistance())
        store.setManualGap(plan,0,true);store.setAllReviewedDirections(batch.scope,plan.evidenceFingerprint)
        return store to cfg
    }
    @Test fun `verified counter refresh preserves geometry and retires legacy confirmations atomically`() = runBlocking {
        val (store,cfg)=onlineConfirmedStore();val old=store.pointPlanningStorage.first()
        val fresh=cfg.copy(config=kotlinx.serialization.json.JsonObject(cfg.config+mapOf("run_log_num" to kotlinx.serialization.json.JsonPrimitive(2))))
        store.refreshVerified(fresh,cfg,old.connectionEpoch) { true }
        val next=store.pointPlanningStorage.first()
        assertEquals(fresh,next.config);assertTrue(next.connectionEpoch>old.connectionEpoch)
        assertNull(next.manualGaps)
        assertNull(next.reviewedDirections)
        val graph=ObservedRoadGraphBuilder.build(fresh,next.roadEvidence!!,io.github.ibudaoa.maps.AmapDistance(),next.gapEvidence,next.manualGaps,automatic=true)
        assertEquals(2,graph.edges.size)
        assertEquals(old.roadEvidence!!.legs,next.roadEvidence!!.legs)
        assertTrue(runCatching { store.setManualGap(RoadGapPlan.create(cfg,old.roadEvidence!!,io.github.ibudaoa.maps.AmapDistance()),0,false) }.isFailure)
    }
    @Test fun `obsolete manual gap index cannot block compatible online rules refresh`() = runBlocking {
        val backend=MemoryStore();val store=DiagnosticStore(backend);val cfg=RoadFixture.config()
        store.save(cfg,captured=true)
        val batch=RoadBatchPlan.create(cfg,store.pointPlanningStorage.first().connectionEpoch)
        val evidence=RoadEvidenceSnapshot(batch.scope,listOf(CandidateRoadEvidence("a","b",RoadFixture.leg())))
        store.saveRoadCheck(RoadBatchReport(batch.scope,RoadBatchPhase.INTERRUPTED,batch.candidateCount,batch.requests.size,listOf(RoadLinkSummary("a","b"))),evidence)
        backend.edit { it[stringPreferencesKey("private_manual_road_gaps_v1")]=ManualRoadGapsCodec.encode(ManualRoadGaps(RoadGapPlan.fingerprint(evidence),setOf(23))) }
        val old=store.pointPlanningStorage.first()
        val fresh=cfg.copy(config=kotlinx.serialization.json.JsonObject(cfg.config+("run_log_num" to kotlinx.serialization.json.JsonPrimitive(2))))
        val next=store.refreshVerified(fresh,cfg,old.connectionEpoch) { true }
        assertEquals(evidence.legs,next.roadEvidence!!.legs)
        assertNull(next.manualGaps)
        assertEquals(2,io.github.ibudaoa.running.RunningRoadSnapshot.build(next,fresh,io.github.ibudaoa.maps.AmapDistance()).graph.edges.size)
    }
    @Test fun `stale online authority and incompatible zone never overwrite reviewed campus`() = runBlocking {
        val (store,cfg)=onlineConfirmedStore();val before=store.pointPlanningStorage.first()
        val fresh=cfg.copy(config=kotlinx.serialization.json.JsonObject(cfg.config+mapOf("run_log_num" to kotlinx.serialization.json.JsonPrimitive(2))))
        assertTrue(runCatching { store.refreshVerified(fresh,cfg,before.connectionEpoch) { false } }.isFailure)
        assertEquals(before,store.pointPlanningStorage.first())
        assertTrue(runCatching { store.refreshVerified(fresh,cfg,before.connectionEpoch+1) { true } }.isFailure)
        assertEquals(before,store.pointPlanningStorage.first())
        assertTrue(runCatching { store.refreshVerified(RoadFixture.config(zone=4),cfg,before.connectionEpoch) { true } }.isFailure)
        assertEquals(before,store.pointPlanningStorage.first())
    }
    @Test fun `authority lost during refresh rolls back all scoped road and config writes`() = runBlocking {
        val (store,cfg)=onlineConfirmedStore();val before=store.pointPlanningStorage.first();var checks=0
        val fresh=cfg.copy(config=kotlinx.serialization.json.JsonObject(cfg.config+mapOf("run_log_num" to kotlinx.serialization.json.JsonPrimitive(2))))
        assertTrue(runCatching { store.refreshVerified(fresh,cfg,before.connectionEpoch) { ++checks==1 } }.isFailure)
        assertEquals(before,store.pointPlanningStorage.first())
    }

    @Test fun `bulk road review restores missing confirmations atomically and survives online refresh`() = runBlocking {
        val (store,cfg)=onlineConfirmedStore()
        val original=store.pointPlanningStorage.first()
        store.setReviewedDirection(original.roadEvidence!!.scope,""+RoadGapPlan.fingerprint(original.roadEvidence!!),"a" to "b",false)
        val plan=RoadGapPlan.create(cfg,original.roadEvidence!!,io.github.ibudaoa.maps.AmapDistance())
        store.setManualGap(plan,0,false)
        assertNull(store.pointPlanningStorage.first().manualGaps)
        store.confirmAllRoadConnectionsAndDirections(plan)
        val restored=store.pointPlanningStorage.first()
        assertEquals(setOf(0),restored.manualGaps!!.indices)
        assertEquals(setOf("a" to "b"),restored.reviewedDirections!!.reversible)
        val graph=ObservedRoadGraphBuilder.build(cfg,restored.roadEvidence!!,io.github.ibudaoa.maps.AmapDistance(),restored.gapEvidence,restored.manualGaps)
        assertEquals(2,ReviewedRoadDirectionsBuilder.apply(graph,restored.reviewedDirections!!,restored.manualGaps).edges.size)
        val fresh=cfg.copy(config=kotlinx.serialization.json.JsonObject(cfg.config+mapOf("run_log_num" to kotlinx.serialization.json.JsonPrimitive(3))))
        store.refreshVerified(fresh,cfg,restored.connectionEpoch) { true }
        val next=store.pointPlanningStorage.first()
        assertNull(next.manualGaps)
        assertNull(next.reviewedDirections)
        assertTrue(runCatching { store.confirmAllRoadConnectionsAndDirections(plan) }.isFailure)
        assertEquals(next,store.pointPlanningStorage.first())
    }

    @Test fun `bulk road review rejects incomplete changed or stale displayed plans without writes`() = runBlocking {
        val (store,cfg)=onlineConfirmedStore();val before=store.pointPlanningStorage.first()
        val plan=RoadGapPlan.create(cfg,before.roadEvidence!!,io.github.ibudaoa.maps.AmapDistance())
        assertTrue(runCatching { store.confirmAllRoadConnectionsAndDirections(plan.copy(gaps=emptyList())) }.isFailure)
        assertTrue(runCatching { store.confirmAllRoadConnectionsAndDirections(plan.copy(eligibleGapCount=plan.eligibleGapCount+1)) }.isFailure)
        assertTrue(runCatching { store.confirmAllRoadConnectionsAndDirections(plan.copy(evidenceFingerprint="0".repeat(64))) }.isFailure)
        assertEquals(before,store.pointPlanningStorage.first())
    }

    @Test fun `online sync returns ready committed roads without waiting for a UI collector`() = runBlocking {
        val (store,cfg)=onlineConfirmedStore();val before=store.pointPlanningStorage.first()
        val changedRules=kotlinx.serialization.json.Json.parseToJsonElement(
            """[{"start_time":0,"end_time":86399,"min_distance":2,"max_distance":12,"min_pace":180,"max_pace":600}]""")
        val fresh=cfg.copy(config=kotlinx.serialization.json.JsonObject(cfg.config+mapOf("time_rule_arr" to changedRules,
            "auth_num" to kotlinx.serialization.json.JsonPrimitive(101),
            "school_auth_num" to kotlinx.serialization.json.JsonPrimitive(202),
            "run_tip_type" to kotlinx.serialization.json.JsonPrimitive(1))))
        val committed=store.refreshVerified(fresh,cfg,before.connectionEpoch) { true }
        val ready=io.github.ibudaoa.running.RunningRoadSnapshot.build(committed,fresh,io.github.ibudaoa.maps.AmapDistance())
        assertEquals(fresh,ready.storage.config)
        assertEquals(before.connectionEpoch+1,ready.graph.scope.connectionEpoch)
        assertEquals(2,ready.graph.edges.size)
        assertEquals(0,ready.graph.manualConfirmationCount)
        assertEquals(0,ready.graph.reviewedTwoWayRoadCount)
        assertNull(committed.manualGaps)
        assertEquals(committed.roadEvidence!!.scope,ready.graph.scope)
    }

    @Test fun `committed sync refuses mixed stale and untrusted road snapshots`() = runBlocking {
        val (store,cfg)=onlineConfirmedStore();val before=store.pointPlanningStorage.first()
        val fresh=cfg.copy(config=kotlinx.serialization.json.JsonObject(cfg.config+mapOf("run_log_num" to kotlinx.serialization.json.JsonPrimitive(4))))
        val committed=store.refreshVerified(fresh,cfg,before.connectionEpoch) { true }
        fun fails(snapshot:PointPlanningStorage,verified:io.github.ibudaoa.diagnostics.DiagnosticConfig=fresh)=
            runCatching { io.github.ibudaoa.running.RunningRoadSnapshot.build(snapshot,verified,io.github.ibudaoa.maps.AmapDistance()) }.isFailure
        assertTrue(fails(before))
        assertTrue(fails(committed.copy(roadEvidence=before.roadEvidence)))
        assertTrue(fails(committed.copy(connectionEpoch=before.connectionEpoch)))
        assertTrue(fails(committed.copy(captured=false)))
        assertTrue(fails(committed.copy(roadReport=null)))
        assertTrue(fails(committed,cfg))
        assertEquals(committed,store.pointPlanningStorage.first())
    }

    @Test fun `authority lost after commit prevents returning a usable synchronized snapshot`() = runBlocking {
        val (store,cfg)=onlineConfirmedStore();val before=store.pointPlanningStorage.first();var calls=0
        val fresh=cfg.copy(config=kotlinx.serialization.json.JsonObject(cfg.config+mapOf("run_log_num" to kotlinx.serialization.json.JsonPrimitive(5))))
        assertTrue(runCatching { store.refreshVerified(fresh,cfg,before.connectionEpoch) { ++calls<=2 } }.isFailure)
        val stored=store.pointPlanningStorage.first()
        assertEquals(fresh,stored.config)
        assertNull(stored.manualGaps)
        assertNull(stored.reviewedDirections)
    }

    private class MemoryStore : DataStore<Preferences> {
        private val mutable = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = mutable
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            mutex.lock()
            try { return transform(mutable.value).also { mutable.value = it } }
            finally { mutex.unlock() }
        }
    }
}

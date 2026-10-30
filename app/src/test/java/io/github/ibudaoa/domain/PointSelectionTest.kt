package io.github.ibudaoa.domain

import io.github.ibudaoa.diagnostics.DiagnosticCodec
import io.github.ibudaoa.diagnostics.DiagnosticConfig
import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Distances are synthetic and explicit: no Android GPS, map SDK or platform requests here. */
class PointSelectionTest {
    private val day = LocalDate.of(2026, 10, 28)
    private val origin = PlatformOrigin(CampusCoordinate(30.0, 114.0))
    private val metric = object : PlatformDistance {
        override val implementationId = "synthetic-distance-v1"
        override fun meters(from: CampusCoordinate, to: CampusCoordinate) =
            if (from == to) 0.0 else to.latitude
    }

    private fun config(count: Int = 2, distances: List<Int> = listOf(100, 200, 300, 400),
        minKm: Double = 0.01, maxKm: Double = 1.0, rootExtra: String = ""): DiagnosticConfig {
        val points = distances.mapIndexed { i, value ->
            // Longitude carries an ID-like unique position; the injected metric uses latitude.
            """{"id":"p$i","jingwei":"${value / 10.0},114.$i","type":2,"point_type":1,"is_online":1,"is_del":0,"ctrl_status":1}"""
        }.joinToString(",")
        return DiagnosticCodec.decode("""{"school_id":1,"term_id":2,"run_zone_id":3,
            "role":1,"set_point_type":1,"point_type":1,"repeat_num":0,"log_mode":1,"is_limit_range":0,
            "point_num":0,"run_face_verify_config":{},
            "time_rule_arr":[{"start_time":21600,"end_time":79200,"min_distance":2,"max_distance":10,
             "max_pace":180,"min_pace":540,"min_log_num":$count,"point_num_online":$count,"point_num_offline":0}],
            "run_line_info":{"point_num":0,"point_min_distance":$minKm,"point_max_distance1":$maxKm,
             "point_max_distance2":$maxKm,"point_update_distance":0.5,"point_list":[$points]},
            "run_zone_latlng":["113,29","115,29","114,31"],"run_zone_latlng_invalid":[]$rootExtra}""")
    }

    private fun policy(config: DiagnosticConfig = config()) = PointPolicyFactory.create(config, 30000, metric.implementationId)
    private fun engine(seed: Int = 42, distance: PlatformDistance = metric) = PointSelectionEngine(distance, Random(seed))
    private fun selected(policy: PointSelectionPolicy = policy(), epoch: Long = 1) =
        (engine().resolve(policy, epoch, day, origin, null) as PointResolution.Selected).cache
    private fun assertBlocked(result: PointResolution, problem: PointSelectionProblem) =
        assertEquals(PointResolution.Blocked(problem), result)

    @Test fun `rule count overrides root and line counters without hardcoded two`() {
        val policy = policy(config(count = 3))
        assertEquals(3, policy.count)
        assertEquals(3, selected(policy).selectedIds.size)
    }

    @Test fun `draws unique official ids and does not always choose nearest points`() {
        val policy = policy()
        val pairs = (0..80).map { seed ->
            val cache = (engine(seed).resolve(policy, 1, day, origin, null) as PointResolution.Selected).cache
            assertEquals(2, cache.selectedIds.distinct().size)
            assertTrue(cache.selectedIds.all { id -> id in policy.candidates.map { it.id } })
            cache.selectedIds.toSet()
        }.toSet()
        assertTrue(pairs.size > 1)
        assertTrue(pairs.any { "p3" in it })
    }

    @Test fun `too near is strictly below the source threshold and not less than or equal`() {
        // Synthetic metric reads latitudes: 9m, 10m, 20m.
        val policy = policy(config(distances = listOf(90, 100, 200)))
        val result = selected(policy)
        assertEquals(setOf("p1", "p2"), result.selectedIds.toSet())
    }

    @Test fun `rank distance gate does not become an unauthorized filter on whole normal pool`() {
        val policy = policy(config(distances = listOf(100, 200, 800), maxKm = 0.05))
        val sets = (0..30).map { (engine(it).resolve(policy, 1, day, origin, null) as PointResolution.Selected).cache.selectedIds }
        assertTrue(sets.any { "p2" in it }) // Third point is 80m, outside 50m; two nearer points pass source gate.
    }

    @Test fun `insufficient and outside-rank cases block without altering previous cache`() {
        assertBlocked(engine().resolve(policy(config(distances = listOf(50, 100))), 1, day, origin, null),
            PointSelectionProblem.INSUFFICIENT_POINTS)
        assertBlocked(engine().resolve(policy(config(distances = listOf(100, 200), maxKm = 0.015)), 1, day, origin, null),
            PointSelectionProblem.OUTSIDE_DISTANCE_GATE)
    }

    @Test fun `no location means no draw and no invented start position`() {
        assertBlocked(engine().resolve(policy(), 1, day, null, null), PointSelectionProblem.LOCATION_REQUIRED)
    }

    @Test fun `unsupported modes and mixed pools never silently use the normal branch`() {
        val source = config().config.toString()
        listOf("\"role\":1" to "\"role\":2", "\"set_point_type\":1" to "\"set_point_type\":2",
            "\"repeat_num\":0" to "\"repeat_num\":1", "\"type\":2" to "\"type\":1",
            "\"is_online\":1" to "\"is_online\":0").forEach { (a, b) ->
            val error = runCatching { policy(DiagnosticCodec.decode(source.replace(a, b))) }.exceptionOrNull()
            assertTrue(error is PointSelectionException)
        }
        assertTrue(runCatching { policy(config(count = 1)) }.exceptionOrNull() is PointSelectionException)
    }

    @Test fun `does not select outside the applicable rule or ambiguous overlaps`() {
        val config = config()
        assertTrue(runCatching { PointPolicyFactory.create(config, 18000, metric.implementationId) }.isFailure)
    }

    @Test fun `same day and below refresh distance reuses exactly the old ids`() {
        val policy = policy()
        val cache = selected(policy)
        val result = engine(999).resolve(policy, 1, day, PlatformOrigin(CampusCoordinate(50.0, 114.0)), cache)
        assertEquals(PointResolution.Reused(cache), result)
    }

    @Test fun `refresh movement uses strict greater than with boundary preserved`() {
        val policy = policy()
        val cache = selected(policy)
        fun movement(value: Double) = object : PlatformDistance {
            override val implementationId = metric.implementationId
            override fun meters(from: CampusCoordinate, to: CampusCoordinate) =
                if (from == cache.origin.coordinate && to == CampusCoordinate(31.0, 114.0)) value else 100.0
        }
        val newOrigin = PlatformOrigin(CampusCoordinate(31.0, 114.0))
        assertEquals(PointResolution.Reused(cache), engine(distance = movement(500.0)).resolve(policy, 1, day, newOrigin, cache))
        val moved = engine(distance = movement(500.01)).resolve(policy, 1, day, newOrigin, cache) as PointResolution.Selected
        assertEquals(PointRefreshReason.MOVED, moved.reason)
    }

    @Test fun `date and connection boundaries refresh only preparations`() {
        val policy = policy(); val cache = selected(policy)
        assertEquals(PointRefreshReason.NEW_DAY, (engine().resolve(policy, 1, day.plusDays(1), origin, cache) as PointResolution.Selected).reason)
        assertEquals(PointRefreshReason.NEW_CONNECTION_OR_CONFIGURATION,
            (engine().resolve(policy, 2, day, origin, cache) as PointResolution.Selected).reason)
    }

    @Test fun `table order alone preserves identity while changed coordinates invalidate it`() {
        val first = config()
        val root = first.config.toMutableMap()
        val line = (root["run_line_info"] as kotlinx.serialization.json.JsonObject).toMutableMap()
        line["point_list"] = kotlinx.serialization.json.JsonArray((line["point_list"] as kotlinx.serialization.json.JsonArray).reversed())
        root["run_line_info"] = kotlinx.serialization.json.JsonObject(line)
        assertEquals(policy(first).fingerprint, policy(DiagnosticConfig(kotlinx.serialization.json.JsonObject(root))).fingerprint)
        val changed = DiagnosticCodec.decode(first.config.toString().replace("10.0,114.0", "10.1,114.0"))
        assertFalse(policy(first).fingerprint == policy(changed).fingerprint)
    }

    @Test fun `paused and interrupted runs preserve locked points despite movement or missing location`() {
        val policy = policy(); val draft = selected(policy)
        val cache = draft.lockForExecution().withPlatformConfirmedPoint(draft.selectedIds.first())
        listOf(PointUsePhase.RUNNING, PointUsePhase.PAUSED, PointUsePhase.INTERRUPTED).forEach { phase ->
            assertEquals(PointResolution.Reused(cache), engine(999).resolve(policy, 1, day, null, cache, phase))
            assertEquals(PointResolution.Reused(cache), engine(999).resolve(policy, 1, day,
                PlatformOrigin(CampusCoordinate(89.0, 0.0)), cache, phase))
        }
    }

    @Test fun `active context changes and missing caches require reconciliation without redraw`() {
        val policy = policy(); val cache = selected(policy).lockForExecution()
        assertBlocked(engine().resolve(policy, 2, day, origin, cache, PointUsePhase.PAUSED), PointSelectionProblem.ACTIVE_CONFIGURATION_CHANGED)
        assertBlocked(engine().resolve(policy, 1, day.plusDays(1), origin, cache, PointUsePhase.RUNNING), PointSelectionProblem.ACTIVE_DATE_CHANGED)
        assertBlocked(engine().resolve(policy, 1, day, origin, null, PointUsePhase.INTERRUPTED), PointSelectionProblem.ACTIVE_CACHE_MISSING)
        assertBlocked(engine().resolve(policy, 1, day, origin, cache), PointSelectionProblem.LOCKED_CACHE)
    }

    @Test fun `confirmations are idempotent and restricted to locked selected ids`() {
        val cache = selected().lockForExecution(); val id = cache.selectedIds.first()
        val once = cache.withPlatformConfirmedPoint(id)
        assertEquals(once, once.withPlatformConfirmedPoint(id))
        assertTrue(runCatching { cache.withPlatformConfirmedPoint("other") }.isFailure)
        assertTrue(runCatching { selected().withPlatformConfirmedPoint(id) }.isFailure)
    }

    @Test fun `invalid distance or different adapter never proceeds or changes cache`() {
        val policy = policy()
        val bad = object : PlatformDistance {
            override val implementationId = metric.implementationId
            override fun meters(from: CampusCoordinate, to: CampusCoordinate) = Double.NaN
        }
        assertBlocked(engine(distance = bad).resolve(policy, 1, day, origin, null), PointSelectionProblem.DISTANCE_UNAVAILABLE)
        val other = object : PlatformDistance {
            override val implementationId = "different-adapter"
            override fun meters(from: CampusCoordinate, to: CampusCoordinate) = 100.0
        }
        assertBlocked(engine(distance = other).resolve(policy, 1, day, origin, null), PointSelectionProblem.DISTANCE_UNAVAILABLE)
    }
}

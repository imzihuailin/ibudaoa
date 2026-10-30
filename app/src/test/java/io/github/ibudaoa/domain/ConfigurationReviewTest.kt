package io.github.ibudaoa.domain

import io.github.ibudaoa.diagnostics.DiagnosticCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationReviewTest {
    private val rule = """{"start_time":21600,"end_time":79200,"min_distance":1.5,"max_distance":8,"max_pace":210,"min_pace":600,"min_log_num":2,"point_num_online":2,"point_num_offline":0}"""
    private val point = """{"id":"00017","jingwei":"30.1,114.2","is_online":1,"type":2,"point_type":1}"""
    private fun review(rules: String = rule, points: String = point, boundary: String = "\"114.1,30.0\",\"114.3,30.0\",\"114.2,30.2\"", face: String = "{}") =
        ConfigurationReviewer.review(DiagnosticCodec.decode("""{
            "school_id":1,"run_zone_id":2,"run_start_time":18000,"run_end_time":82800,
            "time_rule_arr":[$rules],"run_line_info":{"point_list":[$points],"point_update_distance":0.5},
            "run_zone_latlng":[$boundary],"run_zone_latlng_invalid":[],"run_face_verify_config":$face
        }"""))

    @Test fun `point latitude-first and boundary longitude-first are parsed separately`() {
        val result = review()
        assertEquals(CampusCoordinate(30.1, 114.2), result.candidates!!.single().coordinate)
        assertEquals(CampusCoordinate(30.0, 114.1), result.allowedBoundary!!.first())
        assertEquals("00017", result.candidates!!.single().id)
    }

    @Test fun `speed field names normalize to seconds per km with fast bound first`() {
        val time = review().rules!!.single()
        assertEquals(210, time.fastestSecondsPerKm)
        assertEquals(600, time.slowestSecondsPerKm)
        assertEquals(1.5, time.minimumKm, 0.0)
        assertEquals(2, time.onlinePoints)
    }

    @Test fun `specific rule overrides broader root times and boundaries remain inclusive`() {
        val result = review()
        assertNull(result.uniqueTimeRule(18000))
        assertNull(result.uniqueTimeRule(21599))
        assertTrue(result.uniqueTimeRule(21600) != null)
        assertTrue(result.uniqueTimeRule(79200) != null)
        assertNull(result.uniqueTimeRule(79201))
    }

    @Test fun `overlapping rules are not silently resolved by taking first`() {
        val result = review(rules = "$rule,$rule")
        assertEquals(2, result.matchingRules(30000).size)
        assertNull(result.uniqueTimeRule(30000))
    }

    @Test fun `duplicate point identity rejects the table rather than selecting by index`() {
        val result = review(points = "$point,$point")
        assertNull(result.candidates)
        assertTrue(ConfigurationIssue.CANDIDATES in result.issues)
    }

    @Test fun `incorrect axis order is not guessed or automatically swapped`() {
        assertNull(review(points = point.replace("30.1,114.2", "114.2,30.1")).candidates)
        assertNull(review(boundary = "\"30.0,114.1\",\"30.0,114.3\",\"30.2,114.2\"").allowedBoundary)
    }

    @Test fun `empty face object is skipped while independent readiness blockers remain`() {
        val result = review()
        assertTrue(ConfigurationIssue.FACE_UNKNOWN !in result.issues)
        assertTrue(ConfigurationIssue.ROAD_NETWORK in result.issues)
        assertTrue(ConfigurationIssue.ADDITIONAL_RULES in result.issues)
    }

    @Test fun `null face config remains unknown`() {
        assertTrue(ConfigurationIssue.FACE_UNKNOWN in review(face = "null").issues)
    }

    @Test fun `explicit verification is unsupported while disabled stages do not prove all readiness`() {
        val required = review(face = """{"start_status":1,"middle_status":0,"stop_status":0}""")
        assertTrue(ConfigurationIssue.FACE_REQUIRED in required.issues)
        val disabled = review(face = """{"start_status":0,"middle_status":0,"stop_status":0}""")
        assertTrue(ConfigurationIssue.FACE_UNKNOWN !in disabled.issues)
        assertTrue(ConfigurationIssue.ROAD_NETWORK in disabled.issues)
    }

    @Test fun `point refresh distance is interpreted as km based on official refresh branch`() {
        assertEquals(500.0, review().pointRefreshMeters!!, 0.0)
    }

    @Test fun `missing or inverted bounds cannot be replaced with convenient constants`() {
        assertNull(review(rules = rule.replace("\"max_pace\":210", "\"max_pace\":700")).rules)
        assertNull(review(rules = rule.replace("\"min_distance\":1.5", "\"min_distance\":9")).rules)
        assertNull(review(rules = "").rules)
    }

    @Test fun `outside official point is reported but retained instead of replaced`() {
        val result = review(points = point.replace("30.1,114.2", "31.0,115.0"))
        assertEquals(1, result.candidates!!.size)
        assertEquals(setOf("00017"), result.outsideBoundaryIds)
        assertTrue(ConfigurationIssue.REGION_RELATION in result.issues)
    }

    @Test fun `point on polygon edge remains on the configured boundary`() {
        val result = review(points = point.replace("30.1,114.2", "30.0,114.2"))
        assertTrue(result.outsideBoundaryIds!!.isEmpty())
    }

    @Test fun `degenerate boundary cannot be presented as a valid region`() {
        val result = review(boundary = "\"114.1,30.0\",\"114.2,30.0\",\"114.3,30.0\"")
        assertNull(result.allowedBoundary)
        assertTrue(ConfigurationIssue.ALLOWED_REGION in result.issues)
    }
}

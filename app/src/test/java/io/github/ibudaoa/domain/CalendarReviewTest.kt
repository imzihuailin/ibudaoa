package io.github.ibudaoa.domain

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class CalendarReviewTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val start = Instant.parse("2026-10-26T00:00:00Z").epochSecond
    private val end = Instant.parse("2027-01-08T00:00:00Z").epochSecond
    private val rule = """{"start_time":21600,"end_time":79200,"min_distance":2,"max_distance":10,
        "max_pace":180,"min_pace":540,"min_log_num":2,"point_num_online":2,"point_num_offline":0}"""
    private fun config(extra: String = "", rules: String = "[$rule]", term: String = "\"term_start_time\":$start,\"term_end_time\":$end,") =
        DiagnosticConfig(Json.parseToJsonElement("""{"school_id":1,"run_zone_id":2,$term
            "run_start_time":18000,"run_end_time":82800,"time_rule_arr":$rules $extra}""") as JsonObject)
    private fun review(config: DiagnosticConfig = config(), now: String = "2026-10-28T04:00:00Z", zone: ZoneId = shanghai) =
        CalendarReviewer.review(config, Instant.parse(now), zone)

    @Test fun `term checks epoch seconds and includes both whole boundary seconds`() {
        val c = config()
        assertEquals(TermPosition.BEFORE, CalendarReviewer.review(c, Instant.ofEpochSecond(start - 1), shanghai).termPosition)
        assertEquals(TermPosition.WITHIN, CalendarReviewer.review(c, Instant.ofEpochSecond(start), shanghai).termPosition)
        assertEquals(TermPosition.WITHIN, CalendarReviewer.review(c, Instant.ofEpochSecond(end, 999_999_999), shanghai).termPosition)
        assertEquals(TermPosition.AFTER, CalendarReviewer.review(c, Instant.ofEpochSecond(end + 1), shanghai).termPosition)
    }

    @Test fun `missing reversed and accidental millisecond term ranges remain unknown`() {
        listOf("", "\"term_start_time\":$end,\"term_end_time\":$start,",
            "\"term_start_time\":${start * 1000},\"term_end_time\":${end * 1000},",
            "\"term_start_time\":0,\"term_end_time\":$end,").forEach {
            val result = review(config(term = it))
            assertEquals(TermPosition.UNKNOWN, result.termPosition)
            assertNull(result.termStart); assertNull(result.termEnd)
        }
    }

    @Test fun `specific rule cannot be widened by broad root fields`() {
        assertEquals(CurrentTimeRule.OUTSIDE, review(now = "2026-10-27T21:59:59Z").currentTimeRule)
        assertEquals(CurrentTimeRule.UNIQUE, review(now = "2026-10-27T22:00:00Z").currentTimeRule)
        assertEquals(CurrentTimeRule.UNIQUE, review(now = "2026-10-28T14:00:00Z").currentTimeRule)
        assertEquals(CurrentTimeRule.OUTSIDE, review(now = "2026-10-28T14:00:01Z").currentTimeRule)
    }

    @Test fun `time zone is explicit rather than the host default`() {
        assertEquals(CurrentTimeRule.UNIQUE, review(now = "2026-10-27T22:00:00Z").currentTimeRule)
        assertEquals(CurrentTimeRule.OUTSIDE, review(now = "2026-10-27T22:00:01Z", zone = ZoneId.of("UTC")).currentTimeRule)
    }

    @Test fun `empty malformed and overlapping rules never select first or use root fallback`() {
        assertEquals(CurrentTimeRule.UNKNOWN, review(config(rules = "[]")).currentTimeRule)
        assertEquals(CurrentTimeRule.UNKNOWN, review(config(rules = "[{}]")).currentTimeRule)
        assertEquals(CurrentTimeRule.OVERLAPPING, review(config(rules = "[$rule,$rule]")).currentTimeRule)
    }

    @Test fun `known server tip codes are mapped without overriding restrictions by local time`() {
        val expected = mapOf(0 to PreparationSignal.NO_REPORTED_RESTRICTION, 1 to PreparationSignal.NON_SCHOOL_TIME,
            2 to PreparationSignal.NON_SCHOOL_TIME, 3 to PreparationSignal.OUTSIDE_TIME,
            4 to PreparationSignal.GRADE_RESTRICTED, 5 to PreparationSignal.OUTSIDE_CYCLE, 6 to PreparationSignal.NO_POINTS)
        expected.forEach { (code, signal) -> assertEquals(signal, review(config(",\"run_tip_type\":$code")).platformSignal) }
        assertEquals(PreparationSignal.OUTSIDE_DATE, review(config(",\"run_tip_type\":3", rules = "[]")).platformSignal)
    }

    @Test fun `missing or unknown preparation status does not become code zero`() {
        assertEquals(PreparationSignal.UNKNOWN, review().platformSignal)
        assertEquals(PreparationSignal.UNKNOWN, review(config(",\"run_tip_type\":100")).platformSignal)
        assertEquals(PreparationSignal.UNKNOWN, review(config(",\"run_tip_type\":null")).platformSignal)
    }

    @Test fun `week presence distinguishes missing empty valid codes and invalid values`() {
        assertEquals(WeekEvidence.MISSING, review().weekEvidence)
        assertEquals(WeekEvidence.EMPTY, review(config(",\"week_time\":[]")).weekEvidence)
        val result = review(config(",\"week_time\":[\"0\",\"6\"]"))
        assertEquals(WeekEvidence.PRESENT, result.weekEvidence)
        assertEquals(setOf(0, 6), result.weekCodes)
        listOf("null", "{}", "[7]", "[true]", "[1.5]").forEach {
            assertEquals(WeekEvidence.INVALID, review(config(",\"week_time\":$it")).weekEvidence)
        }
    }

    @Test fun `all seven codes cover every weekday without needing to guess their mapping`() {
        val all = review(config(",\"week_time\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"0\"]"))
        assertEquals(WeekEvidence.ALL_DAYS, all.weekEvidence)
        assertEquals((0..6).toSet(), all.weekCodes)
        assertEquals(WeekEvidence.PRESENT, review(config(",\"week_time\":[\"0\",\"6\"]")).weekEvidence)
    }

    @Test fun `date rule ids presence never fills in missing actual school dates`() {
        assertFalse(review().schoolDateRuleIdsPresent)
        assertTrue(review(config(",\"dayrule_ids\":\"\"")).schoolDateRuleIdsPresent)
        assertTrue(review(config(",\"dayrule_ids\":\"\"")).schoolDateRuleIdsEmpty)
        assertFalse(review().schoolDateRuleIdsEmpty)
        assertTrue(review(config(",\"dayrule_ids\":\"1,2\"")).schoolDateRuleIdsPresent)
        assertFalse(review(config(",\"dayrule_ids\":123")).schoolDateRuleIdsPresent)
    }
}

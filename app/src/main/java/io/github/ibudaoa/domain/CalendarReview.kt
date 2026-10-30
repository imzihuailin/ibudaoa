package io.github.ibudaoa.domain

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

enum class TermPosition(val label: String) {
    UNKNOWN("学期时间缺失或异常"), BEFORE("尚未进入学期范围"),
    WITHIN("位于学期时间范围内"), AFTER("已超出学期范围"),
}

enum class CurrentTimeRule(val label: String) {
    UNKNOWN("具体时段规则待核对"), OUTSIDE("当前不在具体规则时段内"),
    UNIQUE("当前命中唯一具体时段"), OVERLAPPING("当前命中多个冲突时段"),
}

enum class PreparationSignal(val label: String) {
    UNKNOWN("平台准备状态未知"), NO_REPORTED_RESTRICTION("该次响应未报告已识别的准备限制"),
    NON_SCHOOL_TIME("该次响应提示非学校规则锻炼时间"),
    OUTSIDE_TIME("该次响应提示不在跑步时段内"),
    OUTSIDE_DATE("该次响应提示不在跑步日期内"),
    GRADE_RESTRICTED("该次响应提示年级不在跑步范围内"),
    OUTSIDE_CYCLE("该次响应提示不在跑步周期内"),
    NO_POINTS("该次响应提示学校跑区未配置打卡点"),
}

enum class WeekEvidence(val label: String) {
    MISSING("星期配置缺失"), INVALID("星期配置格式异常"),
    ALL_DAYS("星期配置覆盖全部七天"), EMPTY("星期不作额外限制"), PRESENT("已读取星期规则"),
}
enum class WeekPosition { UNKNOWN, ALLOWED, BLOCKED }
enum class BroadTimePosition { UNKNOWN, WITHIN, OUTSIDE }

data class CalendarReview(
    val termStart: Instant?, val termEnd: Instant?, val termPosition: TermPosition,
    val currentTimeRule: CurrentTimeRule, val platformSignal: PreparationSignal,
    val weekEvidence: WeekEvidence, val weekCodes: Set<Int>?,
    val schoolDateRuleIdsPresent: Boolean,
    val schoolDateRuleIdsEmpty: Boolean = false,
    val weekPosition:WeekPosition=WeekPosition.UNKNOWN,
    val broadTimePosition:BroadTimePosition=BroadTimePosition.UNKNOWN,
)

/** Read-only partial review. No canRun flag: term/window/tip=0 do not prove complete school eligibility. */
object CalendarReviewer {
    fun review(config: DiagnosticConfig, now: Instant, campusTimeZone: ZoneId): CalendarReview {
        val root = config.config
        val start = root.epoch("term_start_time")
        val end = root.epoch("term_end_time")
        val validTerm = start != null && end != null && !end.isBefore(start)
        val term = when {
            !validTerm -> TermPosition.UNKNOWN
            now.epochSecond < start!!.epochSecond -> TermPosition.BEFORE
            now.epochSecond > end!!.epochSecond -> TermPosition.AFTER
            else -> TermPosition.WITHIN
        }
        val rules = ConfigurationReviewer.review(config).rules
        val matches = rules?.count { it.contains(now.atZone(campusTimeZone).toLocalTime().toSecondOfDay()) }
        val time = when {
            rules.isNullOrEmpty() -> CurrentTimeRule.UNKNOWN // No invented fallback to broad root fields.
            matches == 0 -> CurrentTimeRule.OUTSIDE
            matches == 1 -> CurrentTimeRule.UNIQUE
            else -> CurrentTimeRule.OVERLAPPING
        }
        val signal = when (root.integer("run_tip_type")) {
            0 -> PreparationSignal.NO_REPORTED_RESTRICTION
            1, 2 -> PreparationSignal.NON_SCHOOL_TIME
            3 -> if ((root["time_rule_arr"] as? JsonArray)?.isNotEmpty() == true)
                PreparationSignal.OUTSIDE_TIME else PreparationSignal.OUTSIDE_DATE
            4 -> PreparationSignal.GRADE_RESTRICTED
            5 -> PreparationSignal.OUTSIDE_CYCLE
            6 -> PreparationSignal.NO_POINTS
            else -> PreparationSignal.UNKNOWN
        }
        val week = root["week_time"]
        val codes = (week as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull }
        val weekEvidence = when {
            week == null -> WeekEvidence.MISSING
            codes == null || codes.any { it == null || it !in 0..6 } -> WeekEvidence.INVALID
            codes.isEmpty() -> WeekEvidence.EMPTY
            codes.toSet() == (0..6).toSet() -> WeekEvidence.ALL_DAYS
            else -> WeekEvidence.PRESENT
        }
        val dateIds = (root["dayrule_ids"] as? JsonPrimitive)?.takeIf {
            it.isString && it.content.matches(Regex("[0-9, ]{0,128}"))
        }?.content
        // Official BeforeRunFragment.P3 uses Calendar.DAY_OF_WEEK - 1: Sunday=0.
        val weekPosition=when(weekEvidence) {
            WeekEvidence.ALL_DAYS,WeekEvidence.EMPTY -> WeekPosition.ALLOWED
            WeekEvidence.PRESENT -> if(now.atZone(campusTimeZone).dayOfWeek.value%7 in codes.orEmpty()) WeekPosition.ALLOWED else WeekPosition.BLOCKED
            else -> WeekPosition.UNKNOWN
        }
        val broadStart=root.integer("run_start_time")
        val broadEnd=root.integer("run_end_time")
        val second=now.atZone(campusTimeZone).toLocalTime().toSecondOfDay()
        val broad=when {
            broadStart==null || broadEnd==null || broadStart !in 0..86399 || broadEnd !in broadStart..86400 -> BroadTimePosition.UNKNOWN
            second in broadStart..broadEnd -> BroadTimePosition.WITHIN
            else -> BroadTimePosition.OUTSIDE
        }
        return CalendarReview(
            start.takeIf { validTerm }, end.takeIf { validTerm }, term, time, signal, weekEvidence,
            if (weekEvidence in setOf(WeekEvidence.EMPTY, WeekEvidence.PRESENT, WeekEvidence.ALL_DAYS)) codes!!.filterNotNull().toSet() else null,
            dateIds != null, dateIds?.isBlank() == true,weekPosition,broad,
        )
    }

    private fun JsonObject.integer(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
    private fun JsonObject.epoch(key: String): Instant? = (get(key) as? JsonPrimitive)?.longOrNull
        // Epoch SECONDS, not milliseconds; bound to years supported by the diagnostic date display.
        ?.takeIf { it in 1..253_402_300_799L }?.let(Instant::ofEpochSecond)
}

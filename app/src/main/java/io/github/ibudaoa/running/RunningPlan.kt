package io.github.ibudaoa.running

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.domain.*
import io.github.ibudaoa.roads.FixedPointRoute
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** A plan is not a platform task, uploaded trajectory or valid record. */
enum class RunningPlanPhase { IDLE, CHECKING, SYNCING, WAITING_LOCATION, CONNECTING_ROUTE, REVIEW_ORIGIN, PLANNED, BLOCKED, INTERRUPTED }
enum class RunningPlanProblem(val label:String) {
    ACCOUNT("登录或在线核对尚未完成"), ONLINE_EXPIRED("在线核对已过期，请重新生成计划"),
    CONFIGURATION_CHANGED("学校、跑区或道路配置已变化，原有数据已保留"),
    SCHOOL_RULES("当前学校规则分支尚未适配"), TERM("当前不在学期范围内"),
    WEEK("今天不符合星期规则，或星期配置未知"), DATE_RULE("当前日期规则尚未完整适配"),
    TIME("当前不在允许的跑步时段"), PLATFORM_RESTRICTION("平台提示当前不符合跑前条件"),
    FACE("当前核验要求尚未适配"), LOCATION("请等待新鲜准确的前台定位"),
    SYNC_INCOMPLETE("最新规则与本地道路同步未完成，请重新生成计划"),
    ROAD_REVIEW("当前道路连接确认缺失，请核对并保存道路连接及往返"),
    ROADS("当前道路尚未就绪"), ROAD_UNREACHABLE("当前点位之间没有可用道路路线"), ROAD_DISTANCE("当前道路无法满足距离要求"), ROAD_CALCULATION("道路路线计算未完成"), ORIGIN("请先核对当前位置到道路的连接"),
    PACE_MARGIN("配速区间不足以留出必要余量"), TIME_REMAINING("当前时段剩余时间不足以完成计划"),
    CANCELLED("已离开前台或取消，计划已失效"),
    TASK_STATE("已有未核对的运动状态，请先核对平台记录"),
    ACCESS_ROUTE("地图尚未返回可用的步行接入建议，请稍后重试"),
    POINT_SELECTION("当前位置暂未满足学校的选点条件"),
}
data class RunningPlanSummary(val phase:RunningPlanPhase=RunningPlanPhase.IDLE,val problem:RunningPlanProblem?=null,
    val distanceMeters:Int?=null,val paceSecondsPerKm:Int?=null,val durationSeconds:Int?=null,val pointCount:Int?=null,
    val accessDistanceMeters:Int?=null,val failureStage:String?=null)
internal class RunningPlan(val route:FixedPointRoute,val targetPaceSecondsPerKm:Int,val durationSeconds:Int)
object RunningPlanRules {
    fun problem(config:DiagnosticConfig,now:Instant,zone:ZoneId):RunningPlanProblem? {
        val calendar=CalendarReviewer.review(config,now,zone)
        if(config.stageVerification !in setOf(io.github.ibudaoa.diagnostics.StageVerification.SKIPPED_EMPTY,io.github.ibudaoa.diagnostics.StageVerification.DISABLED)) return RunningPlanProblem.FACE
        if(calendar.termPosition!=TermPosition.WITHIN) return RunningPlanProblem.TERM
        if(calendar.weekPosition!=WeekPosition.ALLOWED) return RunningPlanProblem.WEEK
        if(!calendar.schoolDateRuleIdsPresent || !calendar.schoolDateRuleIdsEmpty) return RunningPlanProblem.DATE_RULE
        if(calendar.currentTimeRule!=CurrentTimeRule.UNIQUE || calendar.broadTimePosition!=BroadTimePosition.WITHIN) return RunningPlanProblem.TIME
        if(calendar.platformSignal!=PreparationSignal.NO_REPORTED_RESTRICTION) return RunningPlanProblem.PLATFORM_RESTRICTION
        return null
    }
    internal fun create(config:DiagnosticConfig,route:FixedPointRoute,now:Instant,zone:ZoneId):Pair<RunningPlan?,RunningPlanSummary> {
        fun blocked(p:RunningPlanProblem)=null to RunningPlanSummary(RunningPlanPhase.BLOCKED,p)
        problem(config,now,zone)?.let { return blocked(it) }
        val second=now.atZone(zone).toLocalTime().toSecondOfDay()
        val rule=ConfigurationReviewer.review(config).uniqueTimeRule(second) ?: return blocked(RunningPlanProblem.SCHOOL_RULES)
        val candidates=ConfigurationReviewer.review(config).candidates ?: return blocked(RunningPlanProblem.SCHOOL_RULES)
        val expectedScope=runCatching { io.github.ibudaoa.roads.CandidateRoadRequest.create(config,route.scope.connectionEpoch,candidates[0].id,candidates[1].id).scope }.getOrNull()
        if(route.scope!=expectedScope || !route.includesReviewedOrigin || route.legs.isEmpty() ||
            route.selectedIds.size!=rule.requiredCheckIns || route.selectedIds.distinct().size!=route.selectedIds.size ||
            route.visitedOrder.toSet()!=route.selectedIds.toSet()) return blocked(RunningPlanProblem.ROADS)
        if(!route.lowerDistanceMeters.isFinite() || !route.upperDistanceMeters.isFinite() || route.lowerDistanceMeters<=0 || route.upperDistanceMeters<route.lowerDistanceMeters)
            return blocked(RunningPlanProblem.ROADS)
        if(route.lowerDistanceMeters<rule.minimumKm*1000 || route.upperDistanceMeters>rule.maximumKm*1000) return blocked(RunningPlanProblem.ROADS)
        val width=rule.slowestSecondsPerKm-rule.fastestSecondsPerKm
        if(width<4) return blocked(RunningPlanProblem.PACE_MARGIN)
        val pace=rule.fastestSecondsPerKm+max(1,ceil(width*0.05).toInt())
        val earliest=ceil(route.upperDistanceMeters*(rule.fastestSecondsPerKm+1)/1000).toInt()
        val latest=floor(route.lowerDistanceMeters*(rule.slowestSecondsPerKm-1)/1000).toInt()
        if(earliest>latest) return blocked(RunningPlanProblem.PACE_MARGIN)
        val duration=ceil(route.upperDistanceMeters*pace/1000).toInt().coerceIn(earliest,latest)
        val broadEnd=(config.config["run_end_time"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: return blocked(RunningPlanProblem.TIME)
        if(duration+15>minOf(rule.endSecond,broadEnd)-second) return blocked(RunningPlanProblem.TIME_REMAINING)
        val plan=RunningPlan(route,pace,duration)
        return plan to RunningPlanSummary(RunningPlanPhase.PLANNED,null,ceil(route.upperDistanceMeters).toInt(),pace,duration,route.selectedIds.size,
            ceil(route.originAccessMeters).toInt())
    }
}

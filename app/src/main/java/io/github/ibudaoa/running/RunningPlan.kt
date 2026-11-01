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
    ACCOUNT("未连接账号，请重新登录"), ONLINE_EXPIRED("登录状态已变化，请重试"),
    CONFIGURATION_CHANGED("学校配置有更新，请重新同步学校"),
    SCHOOL_RULES("当前学校规则暂不支持"), TERM("当前不在学期内"),
    WEEK("今天不是可跑日"), DATE_RULE("当前日期规则暂不支持"),
    TIME("当前不在跑步时段"), PLATFORM_RESTRICTION("平台当前不允许跑步"),
    FACE("需要人脸核验，当前版本不支持"), LOCATION("等待定位"),
    SYNC_INCOMPLETE("同步超时，请重新生成计划"),
    PLAN_FAILED("自动生成计划失败，请重新生成计划"),
    CANCELLED("已离开软件，计划已失效，请重新生成计划"),
    TASK_STATE("上次运行未核对完，请先核对记录"),
    POINT_SELECTION("当前位置不能选点，请移动后重试"),
}
data class RunningPlanSummary(val phase:RunningPlanPhase=RunningPlanPhase.IDLE,val problem:RunningPlanProblem?=null,
    val distanceMeters:Int?=null,val paceSecondsPerKm:Int?=null,val durationSeconds:Int?=null,val pointCount:Int?=null,
    val accessDistanceMeters:Int?=null,val failureStage:String?=null)
internal class RunningPlan(val route:FixedPointRoute,val targetPaceSecondsPerKm:Int,val durationSeconds:Int,
    val rule:CampusTimeRule,val plannedDate:java.time.LocalDate)
object RunningPlanRules {
    internal fun recheck(config:DiagnosticConfig,plan:RunningPlan,now:Instant,zone:ZoneId):RunningPlanProblem? {
        val (fresh,summary)=create(config,plan.route,now,zone)
        if(fresh==null)return summary.problem ?: RunningPlanProblem.SCHOOL_RULES
        if(fresh.rule!=plan.rule || fresh.plannedDate!=plan.plannedDate || fresh.targetPaceSecondsPerKm!=plan.targetPaceSecondsPerKm)
            return RunningPlanProblem.SCHOOL_RULES
        return null
    }
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
            route.visitedOrder.toSet()!=route.selectedIds.toSet()) return blocked(RunningPlanProblem.PLAN_FAILED)
        if(!route.lowerDistanceMeters.isFinite() || !route.upperDistanceMeters.isFinite() || route.lowerDistanceMeters<=0 || route.upperDistanceMeters<route.lowerDistanceMeters)
            return blocked(RunningPlanProblem.PLAN_FAILED)
        if(route.lowerDistanceMeters<rule.minimumKm*1000 || route.upperDistanceMeters>rule.maximumKm*1000) return blocked(RunningPlanProblem.PLAN_FAILED)
        val width=rule.slowestSecondsPerKm-rule.fastestSecondsPerKm
        if(width<4) return blocked(RunningPlanProblem.PLAN_FAILED)
        val pace=rule.fastestSecondsPerKm+max(1,ceil(width*0.05).toInt())
        val earliest=ceil(route.upperDistanceMeters*(rule.fastestSecondsPerKm+1)/1000).toInt()
        val latest=floor(route.lowerDistanceMeters*(rule.slowestSecondsPerKm-1)/1000).toInt()
        if(earliest>latest) return blocked(RunningPlanProblem.PLAN_FAILED)
        val duration=ceil(route.upperDistanceMeters*pace/1000).toInt().coerceIn(earliest,latest)
        val broadEnd=(config.config["run_end_time"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: return blocked(RunningPlanProblem.TIME)
        if(duration+15>minOf(rule.endSecond,broadEnd)-second) return blocked(RunningPlanProblem.PLAN_FAILED)
        val plan=RunningPlan(route,pace,duration,rule,now.atZone(zone).toLocalDate())
        return plan to RunningPlanSummary(RunningPlanPhase.PLANNED,null,ceil(route.upperDistanceMeters).toInt(),pace,duration,route.selectedIds.size,
            ceil(route.originAccessMeters).toInt())
    }
}

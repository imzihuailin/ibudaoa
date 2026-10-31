package io.github.ibudaoa.capture

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.domain.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*

enum class LivePreparationPhase { NOT_CHECKED, NEEDS_SETUP, READING, MATCHED, CHANGED, FAILED, INTERRUPTED }
enum class PreparationSection { IDENTITY, RULES, POINTS_AND_REGION, OTHER }
data class LivePreparationSnapshot(
    val phase:LivePreparationPhase=LivePreparationPhase.NOT_CHECKED,
    val changed:Set<PreparationSection> = emptySet(),
    val issues:Set<ConfigurationIssue> = emptySet(),
    val calendar:CalendarReview?=null,
    val changedFields:Set<String> = emptySet(),
    val changedStructureFields:Set<String> = emptySet(),
) {
    val label:String get()=when(phase) {
        LivePreparationPhase.NOT_CHECKED -> "在线跑前条件尚未核对"
        LivePreparationPhase.NEEDS_SETUP -> "需补齐一次账号维护材料，已有登录和道路保留"
        LivePreparationPhase.READING -> "正在读取当前账号的最新跑前条件"
        LivePreparationPhase.MATCHED -> "当前账号的在线配置与已保存配置一致"
        LivePreparationPhase.CHANGED -> "最新配置与已保存配置不同，旧配置和道路已保留"
        LivePreparationPhase.FAILED -> "在线跑前核对未完成，登录和道路保留"
        LivePreparationPhase.INTERRUPTED -> "已离开前台，在线核对已停止"
    }
}
internal object LivePreparation {
    fun review(fresh:DiagnosticConfig,saved:DiagnosticConfig?,school:String?,now:Instant):LivePreparationSnapshot {
        val root=fresh.config
        require(school!=null && root["school_id"]?.jsonPrimitive?.content==school)
        val changed=linkedSetOf<PreparationSection>()
        val prior=saved?.config.orEmpty()
        val changedFields=(root.keys+prior.keys).filter { root[it]!=prior[it] }
        changedFields.forEach { key ->
            changed.add(when {
                key in setOf("school_id","term_id","run_zone_id") -> PreparationSection.IDENTITY
                key.startsWith("point") || key.startsWith("run_line") || key.startsWith("run_zone_latlng") -> PreparationSection.POINTS_AND_REGION
                key in setOf("time_rule_arr","running_time","week_time","dayrule_ids","run_face_verify_config") || key.startsWith("min_") || key.startsWith("max_") -> PreparationSection.RULES
                else -> PreparationSection.OTHER
            })
        }
        if(saved?.faceConfigShape!=fresh.faceConfigShape) changed.add(PreparationSection.RULES)
        return LivePreparationSnapshot(if(changed.isEmpty())LivePreparationPhase.MATCHED else LivePreparationPhase.CHANGED,
            changed,ConfigurationReviewer.review(fresh).issues,
            CalendarReviewer.review(fresh,now,ZoneId.of("Asia/Shanghai")),changedFields.toSet(),
            if(saved?.structure==null) emptySet() else ((saved.structure.fieldTypes.keys+fresh.structure?.fieldTypes.orEmpty().keys).filter {
                saved?.structure?.fieldTypes?.get(it)!=fresh.structure?.fieldTypes?.get(it)
            }).map { it.substringBefore('.').substringBefore('[') }.toSet())
    }
    fun report(snapshot:LivePreparationSnapshot)=buildJsonObject {
        put("phase",snapshot.phase.name)
        put("changed_sections",JsonArray(snapshot.changed.sortedBy { it.name }.map { JsonPrimitive(it.name) }))
        val privateKey=Regex("token|cookie|authorization|password|student|avatar|device|photo|face_img|(^|_)uid($|_)|user_id",RegexOption.IGNORE_CASE)
        put("changed_fields",JsonArray(snapshot.changedFields.filter { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}")) && !privateKey.containsMatchIn(it) }
            .sorted().take(128).map { JsonPrimitive(it) }))
        put("changed_structure_fields",JsonArray(snapshot.changedStructureFields.filter { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}")) && !privateKey.containsMatchIn(it) }.sorted().take(128).map { JsonPrimitive(it) }))
        put("configuration_issues",JsonArray(snapshot.issues.sortedBy { it.name }.map { JsonPrimitive(it.name) }))
        put("platform_task_state_verified",false);put("can_start",false)
        snapshot.calendar?.let { c ->
            put("term",c.termPosition.name);put("time_rule",c.currentTimeRule.name)
            put("preparation_signal",c.platformSignal.name);put("week_evidence",c.weekEvidence.name)
        }
    }
}

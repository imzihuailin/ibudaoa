package io.github.ibudaoa.running

import io.github.ibudaoa.diagnostics.DiagnosticConfig

/** Allow changing known temporal/rule/counter fields only. All geometry and unknown policy stay identical. */
object OnlineConfigurationCompatibility {
    private val mutableFields=setOf("time_rule_arr","week_time","running_time","dayrule_ids","term_date","term_date_evidence",
        "term_start_time","term_end_time","run_start_time","run_end_time","min_distance","max_distance","min_pace","max_pace",
        "min_log_num","min_num","max_num","point_num","point_num_online","point_num_offline",
        "run_distance","run_log_num","run_notes_id","run_notes_status","run_notes_time","run_tip_type","cache_time","auth_num","school_auth_num")
    fun compatible(previous:DiagnosticConfig,fresh:DiagnosticConfig):Boolean {
        if(previous.faceConfigShape!=fresh.faceConfigShape) return false
        if(previous.config.filterKeys { it !in mutableFields }!=fresh.config.filterKeys { it !in mutableFields }) return false
        fun stableTypes(config:DiagnosticConfig)=config.structure?.fieldTypes?.filterKeys { path -> path.substringBefore('.').substringBefore('[') !in mutableFields }
        return stableTypes(previous)==stableTypes(fresh)
    }
}

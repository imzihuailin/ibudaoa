package io.github.ibudaoa.running

import io.github.ibudaoa.diagnostics.DiagnosticConfig

/** Allow changing known temporal/rule/counter fields only. All geometry and unknown policy stay identical. */
object OnlineConfigurationCompatibility {
    private val mutableFields=setOf("time_rule_arr","week_time","running_time","dayrule_ids","term_date","term_date_evidence",
        "term_start_time","term_end_time","run_start_time","run_end_time","min_distance","max_distance","min_pace","max_pace",
        "min_log_num","min_num","max_num","point_num","point_num_online","point_num_offline",
        "run_distance","run_log_num","run_notes_id","run_notes_status","run_notes_time","run_tip_type","cache_time","auth_num","school_auth_num")
    fun compatible(previousInput:DiagnosticConfig,freshInput:DiagnosticConfig):Boolean {
        val previous=RunningRoadSnapshot.canonical(previousInput)
        val fresh=RunningRoadSnapshot.canonical(freshInput)
        if(previous.faceConfigShape!=fresh.faceConfigShape) return false
        if(previous.config.filterKeys { it !in mutableFields }!=fresh.config.filterKeys { it !in mutableFields }) return false
        // Response-wide shape includes daily statistics, notes and presentation metadata.
        // These are not road identity. Compare actual projected geometry above; retain
        // shape checks only for protocol checks whose values are not in that projection.
        fun policyTypes(config:DiagnosticConfig)=config.structure?.fieldTypes.orEmpty().filterKeys { path ->
            val root=path.substringBefore('.').substringBefore('[')
            root.startsWith("is_") || root in setOf("android_cheats","run_check","robot_module","log_logic","log_mode")
        }
        return policyTypes(previous)==policyTypes(fresh)
    }
}

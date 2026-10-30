package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Official 4.3.1 local ordinary GPS check, not proof of server acceptance or road continuity. */
object ClientCheckpointRadius {
    fun meters(config: DiagnosticConfig): Double? {
        fun integer(name: String) = (config.config[name] as? JsonPrimitive)?.intOrNull
        if (integer("role") != 1 || integer("authStatus") != 2 || integer("point_type") != 1 ||
            integer("set_point_type") != 1 || integer("repeat_num") != 0 || integer("log_mode") != 1) return null
        val line = config.config["run_line_info"] as? JsonObject ?: return null
        val configured = (line["log_max_distance"] as? JsonPrimitive)?.doubleOrNull
            ?.takeIf { it.isFinite() && it > 0 && it <= 10_000 } ?: return null
        return when (integer("is_location_white_list")) {
            0 -> configured
            1 -> 200.0
            else -> null
        }
    }
}

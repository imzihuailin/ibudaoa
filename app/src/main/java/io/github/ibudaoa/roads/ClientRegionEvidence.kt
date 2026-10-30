package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Official 4.3.1 client behavior only, not proof of platform or server acceptance. */
object ClientRegionEvidence {
    fun forCurrentClient(config: DiagnosticConfig): RouteRegionMode {
        fun field(name: String) = (config.config[name] as? JsonPrimitive)?.intOrNull
        return if (field("role") == 1 && field("authStatus") == 2 && field("log_mode") == 1 &&
            field("point_type") == 1 && field("is_limit_range") == 0 && field("log_logic") == 1)
            RouteRegionMode.CLIENT_FENCE_DISABLED
        else RouteRegionMode.UNKNOWN
    }
}

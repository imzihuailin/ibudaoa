package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Student ordinary GPS branch; official client and user screenshot both label log_mode=1 free order. */
object ClientOrderEvidence {
    fun forCurrentClient(config: DiagnosticConfig): RouteOrderMode {
        fun field(name: String) = (config.config[name] as? JsonPrimitive)?.intOrNull
        return if (field("role") == 1 && field("authStatus") == 2 && field("point_type") == 1 &&
            field("set_point_type") == 1 && field("repeat_num") == 0 && field("log_mode") == 1)
            RouteOrderMode.FREE else RouteOrderMode.UNKNOWN
    }
}

package io.github.ibudaoa.capture

import kotlinx.serialization.json.*

/** Deliberately excludes the optional profile, request bodies, account identifiers and headers. */
object AccountReadReport {
    fun summary(state:CaptureState):JsonObject? {
        if (state.purpose!=CapturePurpose.ACCOUNT_READ_CHECK) return null
        val result=state.accountRead
        if (result!=null && (result.independentRequests !in 0..1 ||
            result.accountMatched && result.outcome!=AccountReadOutcome.PASSED ||
            result.independentRequests==1 && !result.originalSignatureVerified ||
            result.outcome==AccountReadOutcome.READ_ACCEPTED_UNBOUND && (result.independentRequests!=1 ||
                !result.originalSignatureVerified || result.accountMatched || result.reason!=AccountReadReason.RESPONSE_ACCOUNT_MISSING) ||
            result.outcome==AccountReadOutcome.PASSED && (result.independentRequests!=1 || !result.accountMatched || !result.originalSignatureVerified))) return null
        return buildJsonObject {
            put("phase",state.phase.name);put("problem",state.problem.name)
            put("session_retained",false)
            put("blocked_requests",state.blockedRequests.coerceIn(0,1_000_000))
            put("blocked_kinds",JsonArray(state.blockedKinds.sortedBy { it.name }.map { JsonPrimitive(it.name) }))
            result?.let {
                put("outcome",it.outcome.name);put("independent_requests",it.independentRequests)
                put("original_signature_verified",it.originalSignatureVerified);put("account_matched",it.accountMatched)
                it.reason?.let { reason -> put("reason",reason.name) }
            }
        }
    }
}

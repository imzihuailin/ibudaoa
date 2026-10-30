package io.github.ibudaoa.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object ConnectionReport {
    fun encode(state: CaptureState, certificateInstalled: Boolean, configCaptured: Boolean, accountSession:AccountSessionSnapshot?=null): String =
        Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), buildJsonObject {
            put("format", "ibudaoa-connection-report-v1")
            put("app_version",io.github.ibudaoa.BuildConfig.VERSION_NAME)
            put("app_version_code",io.github.ibudaoa.BuildConfig.VERSION_CODE)
            put("phase", state.phase.name)
            put("purpose",state.purpose.name)
            AccountReadReport.summary(state)?.let { put("account_read_check",it) }
            accountSession?.let { put("account_session",AccountSessionReport.summary(it)) }
            put("problem", state.problem.name)
            put("certificate_installed", certificateInstalled)
            put("config_captured", configCaptured)
            put("official_opened", state.officialOpened)
            put("blocked_requests", state.blockedRequests)
            put("blocked_kinds", JsonArray(state.blockedKinds.sortedBy { it.name }.map { JsonPrimitive(it.name) }))
            // No message, raw path, URL, response, certificate identity or credential is exported.
        })
}

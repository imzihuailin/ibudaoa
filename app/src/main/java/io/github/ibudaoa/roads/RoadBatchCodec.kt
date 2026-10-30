package io.github.ibudaoa.roads

import kotlinx.serialization.json.*

/** Only counts/enums/checkpoint IDs/numbers; no polyline, user GPS, SDK text, keys or URLs. */
object RoadBatchCodec {
    private val id = Regex("[A-Za-z0-9_-]{1,64}")
    private val hash = Regex("[a-f0-9]{64}")
    private val json = Json { prettyPrint = true }
    fun encode(report: RoadBatchReport): JsonObject {
        require(report.reviewRevision in 1..RoadBatchReport.CURRENT_REVIEW_REVISION)
        require(report.scope.configHash.matches(hash) && report.scope.connectionEpoch > 0 &&
            report.scope.schoolId > 0 && report.scope.termId > 0 && report.scope.zoneId > 0)
        require(report.candidateCount in 2..24 && report.plannedLinks == report.candidateCount - 1 &&
            report.links.size <= report.plannedLinks && report.links.map { it.toId }.distinct().size == report.links.size)
        if (report.phase == RoadBatchPhase.COMPLETE) require(report.links.size == report.plannedLinks)
        return buildJsonObject {
            put("schema", 1); put("review_revision", report.reviewRevision); put("phase", report.phase.name)
            put("connection_epoch", report.scope.connectionEpoch); put("school_id", report.scope.schoolId)
            put("term_id", report.scope.termId); put("zone_id", report.scope.zoneId)
            put("config_hash", report.scope.configHash); put("candidate_count", report.candidateCount)
            put("planned_links", report.plannedLinks)
            put("links", buildJsonArray { report.links.forEach { link ->
                require(link.fromId.matches(id) && link.toId.matches(id) && link.fromId != link.toId)
                add(buildJsonObject {
                    put("from_id", link.fromId); put("to_id", link.toId)
                    link.failure?.let { put("failure", it.name) }
                    put("issues", buildJsonArray { link.issues.sortedBy { it.name }.forEach { add(it.name) } })
                    fun metric(key: String, value: Double?) { value?.let {
                        require(it.isFinite() && it in 0.0..10_000_000.0)
                        put(key, kotlin.math.round(it * 100) / 100)
                    } }
                    metric("geometry_m", link.distanceMeters); metric("start_offset_m", link.startOffsetMeters)
                    metric("end_offset_m", link.endOffsetMeters)
                    metric("maximum_step_gap_m", link.maximumStepGapMeters)
                    link.leavesBoundary?.let { put("leaves_configured_boundary", it) }
                    put("forbidden_unknown", link.forbiddenUnknown)
                })
            } })
        }
    }
    fun text(report: RoadBatchReport) = json.encodeToString(JsonObject.serializer(), encode(report))
    fun decode(text: String): RoadBatchReport {
        require(text.length <= 32_768)
        val o = json.parseToJsonElement(text) as JsonObject
        fun JsonObject.long(key: String) = (getValue(key) as JsonPrimitive).long
        fun JsonObject.string(key: String) = (getValue(key) as JsonPrimitive).let { require(it.isString); it.content }
        require(o.long("schema") == 1L)
        val links = (o.getValue("links") as JsonArray).map { raw ->
            val l = raw as JsonObject
            fun metric(key: String) = (l[key] as? JsonPrimitive)?.double
            RoadLinkSummary(l.string("from_id"), l.string("to_id"), l["failure"]?.let { RoadLinkFailure.valueOf(l.string("failure")) },
                (l.getValue("issues") as JsonArray).map { RoadGeometryIssue.valueOf((it as JsonPrimitive).content) }.toSet(),
                metric("geometry_m"), metric("start_offset_m"), metric("end_offset_m"),
                (l["leaves_configured_boundary"] as? JsonPrimitive)?.boolean,
                (l.getValue("forbidden_unknown") as JsonPrimitive).boolean, metric("maximum_step_gap_m"))
        }
        val count = o.long("candidate_count"); val planned = o.long("planned_links")
        require(count in 2..24 && planned == count - 1)
        val report = RoadBatchReport(RoadScope(o.long("connection_epoch"), o.long("school_id"), o.long("term_id"),
            o.long("zone_id"), o.string("config_hash")), RoadBatchPhase.valueOf(o.string("phase")), count.toInt(), planned.toInt(), links,
            (o["review_revision"] as? JsonPrimitive)?.int ?: 1)
        encode(report) // Validate decoded numbers and identities with the same constraints.
        return report
    }
}

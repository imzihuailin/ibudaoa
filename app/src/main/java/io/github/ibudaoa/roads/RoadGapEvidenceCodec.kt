package io.github.ibudaoa.roads

import io.github.ibudaoa.domain.CampusCoordinate
import kotlinx.serialization.json.*

/** Private campus geometry and checked outcomes; never included in a diagnostic export. */
object RoadGapEvidenceCodec {
    private const val MAX_BYTES = 1_000_000
    private const val MAX_POINTS = 10_000
    private fun coordinate(point: CampusCoordinate) = buildJsonArray { add(point.latitude); add(point.longitude) }
    fun encode(evidence: RoadGapEvidence): String {
        val s = evidence.scope
        require(s.connectionEpoch > 0 && s.schoolId > 0 && s.termId > 0 && s.zoneId > 0 &&
            s.configHash.matches(Regex("[a-f0-9]{64}")) && evidence.evidenceFingerprint.matches(Regex("[a-f0-9]{64}")))
        require(evidence.records.size <= RoadGapPlan.MAX_REQUESTS && evidence.records.map { it.gap }.distinct().size == evidence.records.size)
        var count = 0
        val root = buildJsonObject {
            put("schema", 1); put("source", WalkingRoadReviewer.SOURCE)
            put("epoch", s.connectionEpoch); put("school", s.schoolId); put("term", s.termId); put("zone", s.zoneId)
            put("config_hash", s.configHash); put("evidence_hash", evidence.evidenceFingerprint)
            put("records", buildJsonArray { evidence.records.forEach { record ->
                fun valid(p: CampusCoordinate) = p.latitude.isFinite() && p.longitude.isFinite() &&
                    p.latitude in -90.0..90.0 && p.longitude in -180.0..180.0
                require(valid(record.gap.from) && valid(record.gap.to) && record.gap.from != record.gap.to)
                val bridge = record.bridge
                if (record.outcome == GapCheckOutcome.EXACT_CONNECTED) {
                    require(bridge != null && bridge.steps.size in 1..2048 && bridge.distanceMeters <= 1_000_000 &&
                        RoadGapReviewer.review(record.gap, listOf(bridge)) == GapCheckOutcome.EXACT_CONNECTED)
                } else require(bridge == null)
                add(buildJsonObject {
                    put("from", coordinate(record.gap.from)); put("to", coordinate(record.gap.to)); put("outcome", record.outcome.name)
                    if (bridge != null) {
                        put("meters", bridge.distanceMeters)
                        put("steps", buildJsonArray { bridge.steps.forEach { step ->
                            count += step.size; require(count <= MAX_POINTS)
                            add(buildJsonArray { step.forEach { add(coordinate(it)) } })
                        } })
                    }
                })
            } })
        }
        return root.toString().also { require(it.toByteArray().size <= MAX_BYTES) }
    }
    fun decode(text: String): RoadGapEvidence {
        require(text.toByteArray().size <= MAX_BYTES)
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '[', '{' -> { depth++; require(depth <= 16) }
                ']', '}' -> { depth--; require(depth >= 0) }
            }
        }
        require(!quoted && depth == 0)
        val o = Json.parseToJsonElement(text) as JsonObject
        fun number(k: String) = (o.getValue(k) as JsonPrimitive).long
        fun string(k: String) = (o.getValue(k) as JsonPrimitive).content
        require(number("schema") == 1L && string("source") == WalkingRoadReviewer.SOURCE)
        fun point(value: JsonElement): CampusCoordinate {
            val a = value as JsonArray; require(a.size == 2)
            return CampusCoordinate((a[0] as JsonPrimitive).double, (a[1] as JsonPrimitive).double)
        }
        val records = (o.getValue("records") as JsonArray).map { value ->
            val r = value as JsonObject
            val outcome = GapCheckOutcome.valueOf((r.getValue("outcome") as JsonPrimitive).content)
            val bridge = (r["steps"] as? JsonArray)?.let { steps ->
                WalkingLeg((r.getValue("meters") as JsonPrimitive).double, steps.map { step -> (step as JsonArray).map(::point) })
            }
            RoadGapRecord(RoadGap(point(r.getValue("from")), point(r.getValue("to"))), outcome, bridge)
        }
        return RoadGapEvidence(RoadScope(number("epoch"), number("school"), number("term"), number("zone"), string("config_hash")),
            string("evidence_hash"), records).also { encode(it) }
    }
}

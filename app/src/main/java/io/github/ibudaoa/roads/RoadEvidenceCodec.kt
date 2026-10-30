package io.github.ibudaoa.roads

import io.github.ibudaoa.domain.CampusCoordinate
import kotlinx.serialization.json.*

/** App-private campus roads, deliberately excluded from PreparationExport and diagnostic imports. */
object RoadEvidenceCodec {
    const val MAX_BYTES = 3 * 1024 * 1024
    const val MAX_POINTS = 50_000
    private val id = Regex("[A-Za-z0-9_-]{1,64}")
    fun encode(snapshot: RoadEvidenceSnapshot): String {
        val scope = snapshot.scope
        require(scope.connectionEpoch > 0 && scope.schoolId > 0 && scope.termId > 0 && scope.zoneId > 0 &&
            scope.configHash.matches(Regex("[a-f0-9]{64}")))
        require(snapshot.legs.size in 1..48 && snapshot.legs.map { it.fromId to it.toId }.distinct().size == snapshot.legs.size)
        var count = 0
        val root = buildJsonObject {
            put("schema", 1); put("source", WalkingRoadReviewer.SOURCE)
            put("epoch", scope.connectionEpoch); put("school", scope.schoolId); put("term", scope.termId)
            put("zone", scope.zoneId); put("config_hash", scope.configHash)
            put("legs", buildJsonArray { snapshot.legs.forEach { record ->
                require(record.fromId.matches(id) && record.toId.matches(id) && record.fromId != record.toId)
                require(record.leg.distanceMeters.isFinite() && record.leg.distanceMeters in 0.001..1_000_000.0)
                require(record.leg.steps.size in 1..2048)
                add(buildJsonObject {
                    put("from", record.fromId); put("to", record.toId); put("meters", record.leg.distanceMeters)
                    put("steps", buildJsonArray { record.leg.steps.forEach { step ->
                        require(step.size >= 2); count += step.size; require(count <= MAX_POINTS)
                        add(buildJsonArray { step.forEach { point ->
                            require(point.latitude.isFinite() && point.longitude.isFinite() &&
                                point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0)
                            add(buildJsonArray { add(point.latitude); add(point.longitude) })
                        } })
                    } })
                })
            } })
        }
        return root.toString().also { require(it.toByteArray().size <= MAX_BYTES) }
    }
    fun decode(text: String): RoadEvidenceSnapshot {
        require(text.toByteArray().size <= MAX_BYTES)
        var depth = 0; var inString = false; var escaped = false
        text.forEach { ch ->
            if (inString) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') inString = false
            } else when (ch) {
                '"' -> inString = true
                '{', '[' -> { depth++; require(depth <= 16) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(!inString && depth == 0)
        val o = Json.parseToJsonElement(text) as JsonObject
        fun JsonObject.long(k: String) = (getValue(k) as JsonPrimitive).long
        fun JsonObject.str(k: String) = (getValue(k) as JsonPrimitive).let { require(it.isString); it.content }
        require(o.long("schema") == 1L && o.str("source") == WalkingRoadReviewer.SOURCE)
        val rows = o.getValue("legs") as JsonArray
        require(rows.size in 1..48)
        var count = 0
        val records = rows.map { raw ->
            val r = raw as JsonObject
            val steps = r.getValue("steps") as JsonArray; require(steps.size in 1..2048)
            CandidateRoadEvidence(r.str("from"), r.str("to"), WalkingLeg((r.getValue("meters") as JsonPrimitive).double,
                steps.map { value ->
                    val points = value as JsonArray; count += points.size; require(count <= MAX_POINTS)
                    points.map { p ->
                        val pair = p as JsonArray; require(pair.size == 2)
                        CampusCoordinate((pair[0] as JsonPrimitive).double, (pair[1] as JsonPrimitive).double)
                    }
                }))
        }
        return RoadEvidenceSnapshot(RoadScope(o.long("epoch"), o.long("school"), o.long("term"), o.long("zone"),
            o.str("config_hash")), records).also { encode(it) }
    }
}

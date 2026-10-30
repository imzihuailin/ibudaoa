package io.github.ibudaoa.roads

/** Explicit review of whole accepted road legs. A gap confirmation alone does not imply two-way travel. */
data class ReviewedRoadDirections(
    val scope: RoadScope,
    val evidenceFingerprint: String,
    val manualGapsSnapshot: String?,
    val reversible: Set<Pair<String, String>>,
)

object ReviewedRoadDirectionsBuilder {
    /** No implicit review or invented geometry. A caller must supply the actual reviewed road pairs. */
    fun apply(graph: ObservedRoadGraph, reviewed: ReviewedRoadDirections,
        manualGaps: ManualRoadGaps?): ObservedRoadGraph {
        require(reviewed.scope == graph.scope && reviewed.evidenceFingerprint == graph.fingerprint)
        val fingerprint = manualGaps?.let { ManualRoadGapsCodec.encode(it) }
        require(reviewed.manualGapsSnapshot == fingerprint)
        require(manualGaps == null || manualGaps.evidenceFingerprint == graph.fingerprint)
        require(graph.manualConfirmationCount == (manualGaps?.indices?.size ?: 0))
        require(reviewed.reversible.size <= 48)
        val originals = graph.edges.associateBy { it.fromId to it.toId }
        require(originals.size == graph.edges.size && reviewed.reversible.all { it in originals })
        val edges = graph.edges.toMutableList()
        val proximity = graph.endpointProximity?.toMutableMap()
        val offsets = graph.offsetLegs.toMutableSet()
        val keys = originals.keys.toMutableSet()
        for (pair in reviewed.reversible.sortedWith(compareBy({ it.first }, { it.second }))) {
            val forward = originals.getValue(pair)
            val reverse = pair.second to pair.first
            if (reverse in keys) continue
            require(forward.points.size >= 2 && forward.meters.isFinite() && forward.meters > 0)
            edges.add(ObservedRoadEdge(reverse.first, reverse.second, forward.points.reversed(), forward.meters))
            keys.add(reverse)
            graph.endpointProximity?.get(pair)?.let { value ->
                proximity?.put(reverse, CheckpointEndpointProximity(value.endWithinRadius, value.startWithinRadius))
            }
            if (pair in graph.offsetLegs) offsets.add(reverse)
        }
        return ObservedRoadGraph(graph.scope, graph.fingerprint, graph.nodes, edges, graph.unjoinedLegs,
            offsets, proximity, graph.topology, graph.verifiedGapBridges, graph.locallyResolvedGaps,
            graph.localResolutionLimited, graph.manualConfirmationCount, reviewed.reversible.size)
    }
}

object ReviewedRoadDirectionsCodec {
    fun encode(value: ReviewedRoadDirections): String {
        val s = value.scope
        require(s.connectionEpoch > 0 && s.schoolId > 0 && s.termId > 0 && s.zoneId > 0 &&
            s.configHash.matches(Regex("[a-f0-9]{64}")) && value.evidenceFingerprint.matches(Regex("[a-f0-9]{64}")))
        require(value.reversible.size <= 48 && value.reversible.all { (a,b) ->
            a.matches(Regex("[A-Za-z0-9_-]{1,64}")) && b.matches(Regex("[A-Za-z0-9_-]{1,64}")) && a != b
        })
        value.manualGapsSnapshot?.let { require(ManualRoadGapsCodec.encode(ManualRoadGapsCodec.decode(it)) == it) }
        return kotlinx.serialization.json.buildJsonObject {
            put("schema", kotlinx.serialization.json.JsonPrimitive(1))
            put("epoch", kotlinx.serialization.json.JsonPrimitive(s.connectionEpoch))
            put("school", kotlinx.serialization.json.JsonPrimitive(s.schoolId))
            put("term", kotlinx.serialization.json.JsonPrimitive(s.termId))
            put("zone", kotlinx.serialization.json.JsonPrimitive(s.zoneId))
            put("config_hash", kotlinx.serialization.json.JsonPrimitive(s.configHash))
            put("evidence", kotlinx.serialization.json.JsonPrimitive(value.evidenceFingerprint))
            value.manualGapsSnapshot?.let { put("manual", kotlinx.serialization.json.JsonPrimitive(it)) }
            put("roads", kotlinx.serialization.json.buildJsonArray {
                value.reversible.sortedWith(compareBy({ it.first }, { it.second })).forEach { (a, b) ->
                    add(kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(a)); add(kotlinx.serialization.json.JsonPrimitive(b)) })
                }
            })
        }.toString().also { require(it.length <= 16_384) }
    }
    fun decode(text: String): ReviewedRoadDirections {
        require(text.length <= 16_384)
        val o = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        fun string(k: String) = (o.getValue(k) as kotlinx.serialization.json.JsonPrimitive).content
        fun number(k: String) = string(k).toLong()
        require(number("schema") == 1L)
        val rows = (o.getValue("roads") as kotlinx.serialization.json.JsonArray).map {
            val a = it as kotlinx.serialization.json.JsonArray; require(a.size == 2)
            (a[0] as kotlinx.serialization.json.JsonPrimitive).content to (a[1] as kotlinx.serialization.json.JsonPrimitive).content
        }
        require(rows.distinct().size == rows.size)
        return ReviewedRoadDirections(RoadScope(number("epoch"), number("school"), number("term"), number("zone"), string("config_hash")),
            string("evidence"), (o["manual"] as? kotlinx.serialization.json.JsonPrimitive)?.content, rows.toSet()).also { encode(it) }
    }
}

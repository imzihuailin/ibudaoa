package io.github.ibudaoa.roads

import kotlinx.serialization.json.*

/** User confirmations refer to indices in one exact, ordered road snapshot, not SDK assertions. */
data class ManualRoadGaps(val evidenceFingerprint: String, val indices: Set<Int>)

object ManualRoadGapsCodec {
    fun encode(value: ManualRoadGaps): String {
        require(value.evidenceFingerprint.matches(Regex("[a-f0-9]{64}")))
        require(value.indices.size <= RoadGapPlan.MAX_REQUESTS && value.indices.all { it in 0 until RoadGapPlan.MAX_REQUESTS })
        return buildJsonObject {
            put("schema", 1); put("fingerprint", value.evidenceFingerprint)
            put("indices", buildJsonArray { value.indices.sorted().forEach { add(it) } })
        }.toString()
    }
    fun decode(text: String): ManualRoadGaps {
        require(text.length <= 4096)
        val o = Json.parseToJsonElement(text).jsonObject
        require(o.getValue("schema").jsonPrimitive.int == 1)
        val items = o.getValue("indices").jsonArray.map { it.jsonPrimitive.int }
        require(items.distinct().size == items.size)
        return ManualRoadGaps(o.getValue("fingerprint").jsonPrimitive.content, items.toSet()).also { encode(it) }
    }
}

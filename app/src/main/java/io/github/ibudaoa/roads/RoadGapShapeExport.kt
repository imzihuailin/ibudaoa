package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.domain.PlatformDistance
import java.math.BigDecimal
import kotlinx.serialization.json.*

/** Local SDK boundary shapes, translated independently per gap. No origin, IDs or absolute coordinates. */
object RoadGapShapeExport {
    fun build(config: DiagnosticConfig, evidence: RoadEvidenceSnapshot, distance: PlatformDistance): JsonObject {
        val plan = RoadGapPlan.create(config, evidence, distance)
        val rows = linkedMapOf<RoadGap, JsonObject>()
        for (record in evidence.legs) {
            val request = CandidateRoadRequest.create(config, evidence.scope.connectionEpoch, record.fromId, record.toId)
            val reply = WalkingReply(request, WalkingRoadReviewer.SOURCE, listOf(record.leg))
            val review = WalkingRoadReviewer.review(request, reply, record.leg, config, distance)
            if (review.issues.any { it != RoadGeometryIssue.DISCONNECTED_STEPS }) continue
            for ((before, after) in record.leg.steps.zipWithNext()) {
                val gap = RoadGap(before.last(), after.first())
                if (gap !in plan.gaps || gap in rows) continue
                fun relative(points: List<CampusCoordinate>) = buildJsonArray {
                    points.forEach { p -> add(buildJsonArray {
                        add(JsonPrimitive(BigDecimal.valueOf(p.latitude).subtract(BigDecimal.valueOf(gap.from.latitude))))
                        add(JsonPrimitive(BigDecimal.valueOf(p.longitude).subtract(BigDecimal.valueOf(gap.from.longitude))))
                    }) }
                }
                fun scales(points: List<CampusCoordinate>) = buildJsonArray {
                    points.forEach { p -> add(buildJsonArray {
                        add(BigDecimal.valueOf(p.latitude).stripTrailingZeros().scale().coerceAtLeast(0))
                        add(BigDecimal.valueOf(p.longitude).stripTrailingZeros().scale().coerceAtLeast(0))
                    }) }
                }
                rows[gap] = buildJsonObject {
                    put("index", plan.gaps.indexOf(gap))
                    put("gap_meters", distance.meters(gap.from, gap.to))
                    put("before_relative", relative(before.takeLast(3)))
                    put("after_relative", relative(after.take(3)))
                    put("before_decimal_places", scales(before.takeLast(3)))
                    put("after_decimal_places", scales(after.take(3)))
                }
            }
        }
        return buildJsonObject {
            put("schema", 1)
            put("units", "delta_latitude_longitude_degrees")
            put("eligible", plan.eligibleGapCount)
            put("sample_limit", RoadGapPlan.MAX_REQUESTS)
            put("samples", JsonArray(rows.values.sortedBy { it.getValue("index").jsonPrimitive.int }))
        }
    }
}

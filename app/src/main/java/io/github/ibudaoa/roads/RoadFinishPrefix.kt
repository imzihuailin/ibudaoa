package io.github.ibudaoa.roads

import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.domain.PlatformDistance

/** Stops on an existing directed road segment. It never draws across missing geometry or skips a bend. */
internal class RoadFinishPrefix(private val distance: PlatformDistance, private var budget: Int = 300_000) {
    var limited = false
        private set
    private fun measure(a: CampusCoordinate, b: CampusCoordinate): Double {
        if (--budget < 0) { limited = true; error("Road finish budget") }
        return distance.meters(a, b).also { require(it.isFinite() && it >= 0) }
    }
    fun take(edge: ObservedRoadEdge, targetMeters: Double): ObservedRoadEdge? = runCatching {
        require(distance.implementationId == WalkingRoadReviewer.DISTANCE && targetMeters.isFinite() && targetMeters > 0)
        require(edge.points.size in 2..20_000)
        val points = mutableListOf(edge.points.first())
        var total = 0.0
        for ((a, b) in edge.points.zipWithNext()) {
            val segment = measure(a, b)
            if (total + segment < targetMeters) {
                points.add(b); total += segment
                continue
            }
            val remaining = targetMeters - total
            var low = 0.0; var high = 1.0
            fun at(fraction: Double) = CampusCoordinate(a.latitude + (b.latitude - a.latitude) * fraction,
                a.longitude + (b.longitude - a.longitude) * fraction)
            repeat(48) {
                val mid = (low + high) / 2
                if (measure(a, at(mid)) < remaining) low = mid else high = mid
            }
            val end = at(high)
            val last = measure(a, end)
            if (points.last() != end) points.add(end)
            require(points.size >= 2 && total + last >= targetMeters)
            return@runCatching ObservedRoadEdge(edge.fromId, "@finish", points.toList(), total + last)
        }
        null
    }.getOrNull()
}

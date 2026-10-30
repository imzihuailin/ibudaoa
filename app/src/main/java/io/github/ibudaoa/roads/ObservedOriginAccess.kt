package io.github.ibudaoa.roads

import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.domain.PlatformDistance

/** In-memory, forward-only access from a position exactly on a previously accepted road segment. */
internal object ObservedOriginAccess {
    const val START_ID = "@origin"
    private const val MAX_SEGMENTS = 50_000

    fun find(graph: ObservedRoadGraph, origin: CampusCoordinate, distance: PlatformDistance): List<ObservedRoadEdge>? {
        if (distance.implementationId != WalkingRoadReviewer.DISTANCE ||
            !origin.latitude.isFinite() || !origin.longitude.isFinite() ||
            origin.latitude !in -90.0..90.0 || origin.longitude !in -180.0..180.0) return null
        if (graph.edges.sumOf { (it.points.size - 1).coerceAtLeast(0).toLong() } > MAX_SEGMENTS) return null
        val result = mutableListOf<ObservedRoadEdge>()
        var outputPoints = 0
        for (edge in graph.edges) {
            for (i in 0 until edge.points.lastIndex) {
                val a = edge.points[i]; val b = edge.points[i + 1]
                if (!ObservedRoadAssembly.onSegment(origin, a, b)) continue
                val suffix = (listOf(origin) + edge.points.drop(i + 1)).fold(mutableListOf<CampusCoordinate>()) { acc, point ->
                    if (acc.lastOrNull() != point) acc.add(point)
                    acc
                }
                if (suffix.size < 2) continue
                val meters = runCatching {
                    suffix.zipWithNext().sumOf { (x, y) -> distance.meters(x, y).also { require(it.isFinite() && it >= 0) } }
                }.getOrNull() ?: return null
                if (meters >= 0.001 && meters <= 1_000_000) {
                    outputPoints += suffix.size
                    if (result.size >= 128 || outputPoints > MAX_SEGMENTS) return null
                    result.add(ObservedRoadEdge(START_ID, edge.toId, suffix.toList(), meters))
                }
            }
        }
        return result.distinctBy { it.toId to it.points }
    }
}

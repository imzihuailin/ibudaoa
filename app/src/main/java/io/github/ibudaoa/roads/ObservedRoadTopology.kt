package io.github.ibudaoa.roads

import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.domain.PlatformDistance

/** Diagnostic weak connectivity only: coordinate coincidence is not proof of a traversable junction. */
data class RoadTopologySummary(
    val vertices: Int,
    val weakComponents: Int,
    val componentsCoveringAllCandidates: Int?,
    val maximumCandidatesInOneComponent: Int?,
)

object ObservedRoadTopology {
    private const val MAX_POINTS = 50_000
    private const val MAX_COMPARISONS = 300_000

    fun inspect(legs: List<WalkingLeg>, candidates: Map<String, CampusCoordinate>, radius: Double?,
        distance: PlatformDistance): RoadTopologySummary? {
        val count = legs.sumOf { leg -> leg.steps.sumOf { it.size.toLong() } }
        if (count == 0L || count > MAX_POINTS || candidates.size !in 2..24) return null
        val index = HashMap<CampusCoordinate, Int>()
        val vertices = ArrayList<CampusCoordinate>()
        val parent = ArrayList<Int>()
        fun node(point: CampusCoordinate): Int = index.getOrPut(point) {
            vertices.add(point)
            parent.add(parent.size)
            parent.lastIndex
        }
        fun root(n: Int): Int {
            var i = n
            while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i] }
            return i
        }
        for (leg in legs) for (step in leg.steps) {
            if (step.size < 2 || step.any { !it.latitude.isFinite() || !it.longitude.isFinite() ||
                    it.latitude !in -90.0..90.0 || it.longitude !in -180.0..180.0 }) return null
            var previous = node(step.first())
            for (point in step.drop(1)) {
                val current = node(point)
                val a = root(previous); val b = root(current)
                if (a != b) parent[a] = b
                previous = current
            }
        }
        val components = vertices.indices.map(::root).toSet().size
        if (radius == null || !radius.isFinite() || radius <= 0 || vertices.size.toLong() * candidates.size > MAX_COMPARISONS)
            return RoadTopologySummary(vertices.size, components, null, null)
        return try {
            val coverage = mutableMapOf<Int, MutableSet<String>>()
            vertices.forEachIndexed { i, point ->
                for ((id, candidate) in candidates) {
                    val meters = distance.meters(point, candidate)
                    require(meters.isFinite() && meters >= 0)
                    if (meters <= radius) coverage.getOrPut(root(i)) { mutableSetOf() }.add(id)
                }
            }
            RoadTopologySummary(vertices.size, components, coverage.values.count { it.size == candidates.size },
                coverage.values.maxOfOrNull { it.size } ?: 0)
        } catch (_: Exception) {
            RoadTopologySummary(vertices.size, components, null, null)
        }
    }
}

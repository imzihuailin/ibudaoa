package io.github.ibudaoa.roads

import io.github.ibudaoa.domain.CampusCoordinate

/** Reuses a directed subpath of one observed step. No snapping, reversal or invented edge. */
internal class ObservedGapResolver(legs: List<WalkingLeg>, private var budget: Int = 300_000) {
    private val steps = legs.flatMap { ObservedRoadAssembly.assemble(it.steps).components }
    private val cache = mutableMapOf<Pair<CampusCoordinate, CampusCoordinate>, List<CampusCoordinate>?>()
    var resolved = 0
        private set
    var budgetExhausted = false
        private set

    fun assemble(steps: List<List<CampusCoordinate>>): ObservedRoadAssembly.Result {
        val original = ObservedRoadAssembly.assemble(steps)
        val augmented = mutableListOf(original.components.first())
        for (next in original.components.drop(1)) {
            val pair = augmented.last().last() to next.first()
            val bridge = if (cache.containsKey(pair)) cache[pair] else find(pair.first, pair.second).also {
                cache[pair] = it
                if (it != null) resolved++
            }
            if (bridge != null) augmented.add(bridge)
            augmented.add(next)
        }
        return ObservedRoadAssembly.assemble(augmented)
    }

    private fun find(from: CampusCoordinate, to: CampusCoordinate): List<CampusCoordinate>? {
        for (step in steps) {
            var path: MutableList<CampusCoordinate>? = null
            for (i in 0 until step.lastIndex) {
                if (--budget < 0) { budgetExhausted = true; return null }
                val a = step[i]; val b = step[i + 1]
                if (path == null && ObservedRoadAssembly.onSegment(from, a, b)) path = mutableListOf(from)
                val active = path ?: continue
                // The first edge must proceed forwards from the supplied start, including zero-length edges.
                val start = if (active.size == 1) from else a
                if (ObservedRoadAssembly.onSegment(to, start, b)) {
                    if (active.last() != to) active.add(to)
                    if (active.size >= 2) return active.toList()
                }
                if (active.last() != b) active.add(b)
            }
        }
        return null
    }
}

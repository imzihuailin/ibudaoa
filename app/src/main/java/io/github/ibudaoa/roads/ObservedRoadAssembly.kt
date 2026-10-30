package io.github.ibudaoa.roads

import io.github.ibudaoa.domain.CampusCoordinate
import java.math.BigDecimal

/** Preserves SDK points and edges. Never generates a point or an edge across missing evidence. */
object ObservedRoadAssembly {
    data class Result(val components: List<List<CampusCoordinate>>, val evidenceJoins: Int) {
        val continuous get() = components.size == 1
    }

    fun assemble(steps: List<List<CampusCoordinate>>): Result {
        require(steps.isNotEmpty() && steps.all { it.size >= 2 && it.all(::valid) })
        val components = mutableListOf(steps.first().toList())
        var joins = 0
        for (next in steps.drop(1)) {
            val previous = components.last()
            val joined = join(previous, next)
            if (joined == null) components.add(next.toList())
            else {
                if (previous.last() != next.first()) joins++
                components[components.lastIndex] = joined
            }
        }
        return Result(components.map { it.toList() }, joins)
    }

    private fun join(a: List<CampusCoordinate>, b: List<CampusCoordinate>): List<CampusCoordinate>? {
        if (a.last() == b.first()) return a + b.drop(1)
        // A common SDK vertex is an observed junction; keep only edges actually returned on either side.
        // Only compare the adjoining segments, not earlier intersections elsewhere in the route.
        val bPositions = b.take(2).withIndex().associate { it.value to it.index }
        for (i in a.lastIndex downTo maxOf(0, a.lastIndex - 1)) {
            val j = bPositions[a[i]] ?: continue
            return a.take(i + 1) + b.drop(j + 1)
        }
        // Split an observed straight segment at an EXISTING SDK point on it, not at an interpolated guess.
        if (onSegment(b.first(), a[a.lastIndex - 1], a.last())) return a.dropLast(1) + b
        if (onSegment(a.last(), b.first(), b[1])) return a + b.drop(1)
        return null
    }

    internal fun onSegment(p: CampusCoordinate, a: CampusCoordinate, b: CampusCoordinate): Boolean {
        // Require exact collinearity of the original decimal coordinates, not a snap radius.
        if (p.latitude !in minOf(a.latitude, b.latitude)..maxOf(a.latitude, b.latitude) ||
            p.longitude !in minOf(a.longitude, b.longitude)..maxOf(a.longitude, b.longitude)) return false
        fun decimal(value: Double) = BigDecimal.valueOf(value)
        val ax = decimal(a.longitude); val ay = decimal(a.latitude)
        val bx = decimal(b.longitude); val by = decimal(b.latitude)
        val px = decimal(p.longitude); val py = decimal(p.latitude)
        val cross = px.subtract(ax).multiply(by.subtract(ay))
            .subtract(py.subtract(ay).multiply(bx.subtract(ax)))
        return cross.signum() == 0 &&
            p.latitude in minOf(a.latitude, b.latitude)..maxOf(a.latitude, b.latitude) &&
            p.longitude in minOf(a.longitude, b.longitude)..maxOf(a.longitude, b.longitude)
    }
    private fun valid(p: CampusCoordinate) = p.latitude.isFinite() && p.longitude.isFinite() &&
        p.latitude in -90.0..90.0 && p.longitude in -180.0..180.0
}

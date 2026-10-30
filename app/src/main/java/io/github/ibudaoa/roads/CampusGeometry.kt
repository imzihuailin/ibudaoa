package io.github.ibudaoa.roads

import io.github.ibudaoa.domain.CampusCoordinate
import kotlin.math.abs

/** Small campus polygons only. Split a segment at every boundary intersection, not fixed samples. */
object CampusGeometry {
    private const val EPS = 1e-12
    private fun normalized(polygon: List<CampusCoordinate>) =
        if (polygon.size > 1 && polygon.first() == polygon.last()) polygon.dropLast(1) else polygon

    fun validPolygon(polygon: List<CampusCoordinate>): Boolean {
        val p = normalized(polygon)
        if (p.size !in 3..1000 || p.distinct().size != p.size || p.any {
                !it.latitude.isFinite() || !it.longitude.isFinite() || it.latitude !in -85.0..85.0 ||
                    it.longitude !in -180.0..180.0
            }) return false
        if (p.maxOf { it.longitude } - p.minOf { it.longitude } > 1.0 ||
            p.maxOf { it.latitude } - p.minOf { it.latitude } > 1.0) return false
        val origin = p.first()
        val area = p.indices.sumOf { i -> cross(sub(p[i], origin), sub(p[(i + 1) % p.size], origin)) }
        if (abs(area) <= EPS) return false
        for (i in p.indices) for (j in i + 1 until p.size) {
            if (j == i + 1 || i == 0 && j == p.lastIndex) continue
            if (cuts(p[i], p[(i + 1) % p.size], p[j], p[(j + 1) % p.size]).isNotEmpty()) return false
        }
        return true
    }

    class Prepared internal constructor(private val points: List<CampusCoordinate>) {
        fun segmentInside(a: CampusCoordinate, b: CampusCoordinate) = insidePrepared(a, b, points)
    }

    fun prepare(polygon: List<CampusCoordinate>): Prepared? =
        if (validPolygon(polygon)) Prepared(normalized(polygon).toList()) else null

    fun segmentInside(a: CampusCoordinate, b: CampusCoordinate, polygon: List<CampusCoordinate>): Boolean =
        prepare(polygon)?.segmentInside(a, b) == true

    private fun insidePrepared(a: CampusCoordinate, b: CampusCoordinate, p: List<CampusCoordinate>): Boolean {
        if (!contains(a, p) || !contains(b, p)) return false
        if (a == b) return true
        val t = (listOf(0.0, 1.0) + p.indices.flatMap { i -> cuts(a, b, p[i], p[(i + 1) % p.size]) })
            .sorted().distinct()
        return t.zipWithNext().all { (left, right) -> contains(at(a, b, (left + right) / 2), p) }
    }

    private fun contains(q: CampusCoordinate, p: List<CampusCoordinate>): Boolean {
        var inside = false
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            if (on(q, a, b)) return true
            if ((a.latitude > q.latitude) != (b.latitude > q.latitude) &&
                q.longitude < (b.longitude - a.longitude) * (q.latitude - a.latitude) /
                (b.latitude - a.latitude) + a.longitude) inside = !inside
        }
        return inside
    }

    private fun on(q: CampusCoordinate, a: CampusCoordinate, b: CampusCoordinate) =
        abs(cross(sub(q, a), sub(b, a))) <= EPS &&
            q.latitude >= minOf(a.latitude, b.latitude) - EPS && q.latitude <= maxOf(a.latitude, b.latitude) + EPS &&
            q.longitude >= minOf(a.longitude, b.longitude) - EPS && q.longitude <= maxOf(a.longitude, b.longitude) + EPS

    private fun cuts(a: CampusCoordinate, b: CampusCoordinate, c: CampusCoordinate, d: CampusCoordinate): List<Double> {
        val r = sub(b, a); val s = sub(d, c); val ca = sub(c, a)
        val den = cross(r, s)
        if (abs(den) > EPS) {
            val t = cross(ca, s) / den; val u = cross(ca, r) / den
            return if (t in -EPS..1.0 + EPS && u in -EPS..1.0 + EPS) listOf(t.coerceIn(0.0, 1.0)) else emptyList()
        }
        if (abs(cross(ca, r)) > EPS) return emptyList()
        val norm = r.longitude * r.longitude + r.latitude * r.latitude
        if (norm <= EPS * EPS) return if (on(a, c, d)) listOf(0.0) else emptyList()
        return listOf(c, d).filter { on(it, a, b) }.map {
            val delta = sub(it, a)
            ((delta.longitude * r.longitude + delta.latitude * r.latitude) / norm).coerceIn(0.0, 1.0)
        }.ifEmpty { if (on(a, c, d) || on(b, c, d)) listOf(0.0, 1.0) else emptyList() }
    }

    private fun sub(a: CampusCoordinate, b: CampusCoordinate) = CampusCoordinate(a.latitude - b.latitude, a.longitude - b.longitude)
    private fun cross(a: CampusCoordinate, b: CampusCoordinate) = a.longitude * b.latitude - a.latitude * b.longitude
    private fun at(a: CampusCoordinate, b: CampusCoordinate, t: Double) =
        CampusCoordinate(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
}

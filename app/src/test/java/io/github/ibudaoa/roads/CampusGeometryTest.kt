package io.github.ibudaoa.roads

import io.github.ibudaoa.domain.CampusCoordinate
import org.junit.Assert.*
import org.junit.Test

class CampusGeometryTest {
    private fun p(x: Double, y: Double) = CampusCoordinate(30 + y / 1000, 114 + x / 1000)
    private val square = listOf(p(0.0, 0.0), p(4.0, 0.0), p(4.0, 4.0), p(0.0, 4.0))
    // A narrow notch: endpoints and the midpoint of the tested segment are all inside.
    private val notched = listOf(p(0.0, 0.0), p(4.0, 0.0), p(4.0, 4.0), p(1.1, 4.0),
        p(1.1, 1.0), p(1.0, 1.0), p(1.0, 4.0), p(0.0, 4.0))

    @Test fun `interior and boundary segments are included regardless of winding`() {
        assertTrue(CampusGeometry.segmentInside(p(0.0, 0.0), p(4.0, 0.0), square))
        assertTrue(CampusGeometry.segmentInside(p(1.0, 1.0), p(3.0, 3.0), square.reversed()))
        assertTrue(CampusGeometry.segmentInside(p(1.0, 1.0), p(1.0, 1.0), square))
        assertTrue(CampusGeometry.validPolygon(square + square.first()))
    }

    @Test fun `a narrow concave exit cannot hide between endpoints or midpoint`() {
        assertTrue(CampusGeometry.validPolygon(notched))
        assertFalse(CampusGeometry.segmentInside(p(0.5, 2.0), p(3.5, 2.0), notched))
        assertTrue(CampusGeometry.segmentInside(p(0.5, 0.5), p(3.5, 0.5), notched))
    }

    @Test fun `outside endpoints are not snapped or enlarged into the region`() {
        assertFalse(CampusGeometry.segmentInside(p(-0.1, 1.0), p(1.0, 1.0), square))
        assertFalse(CampusGeometry.segmentInside(p(1.0, 1.0), p(4.1, 1.0), square))
    }

    @Test fun `invalid bow tie zero area duplicates and world spanning polygons stay unknown`() {
        val invalid = listOf(emptyList(), square.take(2), listOf(p(0.0, 0.0), p(4.0, 4.0), p(0.0, 4.0), p(4.0, 0.0)),
            listOf(p(0.0, 0.0), p(1.0, 0.0), p(2.0, 0.0)), square + square[1],
            listOf(CampusCoordinate(0.0, -179.0), CampusCoordinate(0.0, 179.0), CampusCoordinate(1.0, 179.0)))
        invalid.forEach { assertFalse(CampusGeometry.validPolygon(it)); assertNull(CampusGeometry.prepare(it)) }
    }
}

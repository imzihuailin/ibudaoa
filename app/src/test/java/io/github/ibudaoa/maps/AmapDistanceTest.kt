package io.github.ibudaoa.maps

import io.github.ibudaoa.domain.CampusCoordinate
import org.junit.Assert.*
import org.junit.Test

class AmapDistanceTest {
    private val distance = AmapDistance()

    @Test fun `SDK distance retains metre units and latitude longitude order`() {
        // Public synthetic geometry, not a campus route or recorded user location.
        val origin = CampusCoordinate(30.0, 114.0)
        val north = CampusCoordinate(31.0, 114.0)
        val east = CampusCoordinate(30.0, 115.0)
        assertEquals(111_195.0, distance.meters(origin, north), 500.0)
        assertEquals(96_297.0, distance.meters(origin, east), 500.0)
        assertEquals(0.0, distance.meters(origin, origin), 0.0)
        assertEquals(distance.meters(origin, east), distance.meters(east, origin), 0.01)
    }

    @Test fun `invalid coordinates are rejected before SDK normalization`() {
        val origin = CampusCoordinate(30.0, 114.0)
        listOf(CampusCoordinate(100.0, 114.0), CampusCoordinate(30.0, 181.0),
            CampusCoordinate(Double.NaN, 114.0), CampusCoordinate(30.0, Double.POSITIVE_INFINITY)).forEach {
            assertTrue(runCatching { distance.meters(origin, it) }.isFailure)
            assertTrue(runCatching { distance.meters(it, origin) }.isFailure)
        }
    }
}

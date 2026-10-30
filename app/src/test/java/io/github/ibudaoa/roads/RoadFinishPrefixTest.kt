package io.github.ibudaoa.roads

import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.maps.AmapDistance
import org.junit.Assert.*
import org.junit.Test

class RoadFinishPrefixTest {
    private val metric = AmapDistance()
    private val a = CampusCoordinate(30.0, 114.0)
    private val b = CampusCoordinate(30.001, 114.0)
    private val c = CampusCoordinate(30.001, 114.001)
    private val line = listOf(a,b,c)
    private val edge = ObservedRoadEdge("a", "c", line, line.zipWithNext().sumOf { (x,y) -> metric.meters(x,y) })

    @Test fun `finish follows the existing bend and stops inside its last segment`() {
        val target = metric.meters(a,b) + 30.0
        val result = RoadFinishPrefix(metric).take(edge, target)!!
        assertEquals(listOf(a,b), result.points.take(2))
        assertEquals(b.latitude, result.points.last().latitude, 0.0)
        assertTrue(result.points.last().longitude > b.longitude && result.points.last().longitude < c.longitude)
        assertEquals(target, result.meters, 0.01)
        assertEquals(line, edge.points)
    }

    @Test fun `a requested finish beyond the road cannot create an extension`() {
        assertNull(RoadFinishPrefix(metric).take(edge, edge.meters + 10))
        assertNull(RoadFinishPrefix(metric).take(edge, Double.NaN))
        assertNull(RoadFinishPrefix(metric).take(edge, -1.0))
    }

    @Test fun `bounded work does not turn an incomplete calculation into a route`() {
        val finish = RoadFinishPrefix(metric, 0)
        assertNull(finish.take(edge, 30.0))
        assertTrue(finish.limited)
    }
}

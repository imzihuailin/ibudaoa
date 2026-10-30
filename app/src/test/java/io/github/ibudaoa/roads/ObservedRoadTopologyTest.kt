package io.github.ibudaoa.roads

import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.domain.PlatformDistance
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ObservedRoadTopologyTest {
    private fun p(y: Double, x: Double = 0.0) = CampusCoordinate(y, x)
    private val metric = object : PlatformDistance {
        override val implementationId = "test"
        override fun meters(from: CampusCoordinate, to: CampusCoordinate) =
            (abs(from.latitude - to.latitude) + abs(from.longitude - to.longitude)) * 1000
    }
    private fun leg(vararg steps: List<CampusCoordinate>) = WalkingLeg(1.0, steps.toList())

    @Test fun `exact vertices join only observed segments and gaps remain separate`() {
        val a=p(0.0); val b=p(0.01); val c=p(0.02); val d=p(0.02001); val e=p(0.03)
        val summary = ObservedRoadTopology.inspect(listOf(leg(listOf(a,b),listOf(b,c),listOf(d,e))),
            mapOf("first" to a, "second" to e), 0.1, metric)!!
        assertEquals(5, summary.vertices)
        assertEquals(2, summary.weakComponents)
        assertEquals(0, summary.componentsCoveringAllCandidates)
        assertEquals(1, summary.maximumCandidatesInOneComponent)
    }

    @Test fun `common returned vertex can group records without claiming direction or a valid route`() {
        val a=p(0.0); val b=p(0.01); val c=p(0.02)
        val summary = ObservedRoadTopology.inspect(listOf(leg(listOf(a,b)),leg(listOf(c,b))),
            mapOf("first" to a,"second" to c), 0.1, metric)!!
        assertEquals(1, summary.weakComponents)
        assertEquals(1, summary.componentsCoveringAllCandidates)
        assertEquals(2, summary.maximumCandidatesInOneComponent)
    }

    @Test fun `unknown threshold or excessive comparisons omit coverage`() {
        val a=p(0.0); val b=p(0.01)
        val summary = ObservedRoadTopology.inspect(listOf(leg(listOf(a,b))),mapOf("a" to a,"b" to b),null,metric)!!
        assertEquals(1, summary.weakComponents); assertNull(summary.maximumCandidatesInOneComponent)
        val points = (0..13_000).map { p(it / 100_000.0) }
        val candidates = (0 until 24).associate { it.toString() to a }
        val bounded = ObservedRoadTopology.inspect(listOf(leg(points)),candidates,100.0,metric)!!
        assertNull(bounded.maximumCandidatesInOneComponent)
    }

    @Test fun `invalid points and excessively large data are rejected`() {
        val a=p(0.0); val b=p(0.01); val candidates=mapOf("a" to a,"b" to b)
        assertNull(ObservedRoadTopology.inspect(listOf(leg(listOf(a,p(Double.NaN)))),candidates,1.0,metric))
        assertNull(ObservedRoadTopology.inspect(listOf(leg(List(50_001) { a })),candidates,1.0,metric))
    }
}

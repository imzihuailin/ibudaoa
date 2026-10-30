package io.github.ibudaoa.roads

import io.github.ibudaoa.domain.CampusCoordinate
import org.junit.Assert.*
import org.junit.Test

class ObservedGapResolverTest {
    private fun p(y: Double) = CampusCoordinate(y, 114.0)
    private val a = p(30.0)
    private val b = p(30.001)
    private val c = p(30.001006)
    private val d = p(30.002)
    private val broken = listOf(listOf(a, b), listOf(c, d))

    @Test fun `another observed forward segment supplies a subpath without a new edge`() {
        val resolver = ObservedGapResolver(listOf(WalkingLeg(200.0, listOf(listOf(a, d)))))
        val result = resolver.assemble(broken)
        assertTrue(result.continuous)
        assertEquals(listOf(a, b, c, d), result.components.single())
        assertEquals(1, resolver.resolved)
        resolver.assemble(broken)
        assertEquals(1, resolver.resolved)
    }

    @Test fun `an observed bend is preserved rather than shortened into a straight connector`() {
        val bend = CampusCoordinate(30.001003, 114.0001)
        val resolver = ObservedGapResolver(listOf(WalkingLeg(20.0, listOf(listOf(b, bend, c)))))
        assertEquals(listOf(a, b, bend, c, d), resolver.assemble(broken).components.single())
    }

    @Test fun `reverse direction cannot bridge a gap`() {
        val resolver = ObservedGapResolver(listOf(WalkingLeg(200.0, listOf(listOf(d, a)))))
        assertFalse(resolver.assemble(broken).continuous)
        assertEquals(0, resolver.resolved)
    }

    @Test fun `a verified shared vertex connects consecutive source steps`() {
        val bend = CampusCoordinate(30.001003, 114.0001)
        val resolver = ObservedGapResolver(listOf(WalkingLeg(20.0, listOf(listOf(b, bend), listOf(bend, c)))))
        assertEquals(listOf(a, b, bend, c, d), resolver.assemble(broken).components.single())
    }

    @Test fun `nearby segment and separate steps cannot invent a crossing`() {
        val nearby = listOf(CampusCoordinate(30.0, 114.000001), CampusCoordinate(30.002, 114.000001))
        val resolver = ObservedGapResolver(listOf(WalkingLeg(200.0, broken + listOf(nearby))))
        assertFalse(resolver.assemble(broken).continuous)
    }

    @Test fun `bounded work returns unresolved rather than a fabricated connection`() {
        val resolver = ObservedGapResolver(listOf(WalkingLeg(200.0, listOf(listOf(a, d)))), 0)
        assertFalse(resolver.assemble(broken).continuous)
        assertTrue(resolver.budgetExhausted)
    }
}

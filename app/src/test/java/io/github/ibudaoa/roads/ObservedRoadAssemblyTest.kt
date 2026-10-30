package io.github.ibudaoa.roads

import io.github.ibudaoa.domain.CampusCoordinate
import org.junit.Assert.*
import org.junit.Test

class ObservedRoadAssemblyTest {
    private fun p(y: Double, x: Double = 114.0) = CampusCoordinate(y, x)
    @Test fun `joins exact shared endpoint without duplicating it`() {
        val a=p(30.0); val b=p(30.001); val c=p(30.002)
        val result=ObservedRoadAssembly.assemble(listOf(listOf(a,b),listOf(b,c)))
        assertTrue(result.continuous); assertEquals(listOf(a,b,c),result.components.single()); assertEquals(0,result.evidenceJoins)
    }
    @Test fun `uses an existing shared vertex and only observed edges`() {
        val a=p(30.0); val b=p(30.001); val c=p(30.002); val d=p(30.003)
        val result=ObservedRoadAssembly.assemble(listOf(listOf(a,b,c),listOf(b,d)))
        assertEquals(listOf(a,b,d),result.components.single()); assertEquals(1,result.evidenceJoins)
    }
    @Test fun `endpoint on another observed segment is an evidence based junction`() {
        val a=p(30.0); val c=p(30.002); val b=p(30.001); val d=p(30.003)
        val result=ObservedRoadAssembly.assemble(listOf(listOf(a,c),listOf(b,d)))
        assertEquals(listOf(a,b,d),result.components.single())
        val other=ObservedRoadAssembly.assemble(listOf(listOf(a,b),listOf(p(30.0015),p(30.0005))))
        assertEquals(listOf(a,b,p(30.0005)),other.components.single())
    }
    @Test fun `nearby unobserved gap remains two components regardless of size`() {
        val a=p(30.0); val b=p(30.001); val c=p(30.001006); val d=p(30.002)
        val steps=listOf(listOf(a,b),listOf(c,d))
        val result=ObservedRoadAssembly.assemble(steps)
        assertFalse(result.continuous); assertEquals(steps,result.components)
    }
    @Test fun `near but off segment is not snapped onto a road`() {
        val a=p(30.0); val b=p(30.002); val c=p(30.001,114.00000001); val d=p(30.003,114.00000001)
        assertFalse(ObservedRoadAssembly.assemble(listOf(listOf(a,b),listOf(c,d))).continuous)
    }
    @Test fun `an earlier shared vertex does not join nonadjacent steps`() {
        val a=p(30.0); val b=p(30.001); val c=p(30.002); val d=p(30.003)
        val e=p(30.001,114.001)
        assertFalse(ObservedRoadAssembly.assemble(listOf(listOf(a,b,c,d),listOf(b,e))).continuous)
    }
    @Test fun `invalid or empty SDK geometry is rejected`() {
        listOf(emptyList(), listOf(emptyList()), listOf(listOf(p(30.0))),
            listOf(listOf(p(30.0),p(Double.NaN)))).forEach { assertTrue(runCatching { ObservedRoadAssembly.assemble(it) }.isFailure) }
    }
}

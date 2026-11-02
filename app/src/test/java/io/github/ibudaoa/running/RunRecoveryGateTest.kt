package io.github.ibudaoa.running

import org.junit.Assert.*
import org.junit.Test

class RunRecoveryGateTest {
    @Test fun `all six dependency loading orders wait then claim once`() {
        val orders=listOf(listOf(0,1,2),listOf(0,2,1),listOf(1,0,2),listOf(1,2,0),listOf(2,0,1),listOf(2,1,0))
        for(order in orders) {
            val gate=RunRecoveryGate<String>()
            val loaded=BooleanArray(3)
            order.forEachIndexed { index,part ->
                loaded[part]=true
                assertEquals(index==2,gate.claim("task",loaded.all { it },false))
            }
            assertFalse(gate.claim("task",true,false))
        }
    }
    @Test fun `busy initialization does not consume ready attempt`() {
        val gate=RunRecoveryGate<String>()
        assertFalse(gate.claim("task",true,true))
        assertTrue(gate.claim("task",true,false))
    }
    @Test fun `failed attempt cannot loop as busy flag changes`() {
        val gate=RunRecoveryGate<String>()
        assertTrue(gate.claim("task",true,false))
        assertFalse(gate.claim("task",true,true))
        assertFalse(gate.claim("task",true,false))
        assertTrue(gate.claim("changed dependencies",true,false))
    }
    @Test fun `returning to app permits retry with same dependencies`() {
        val gate=RunRecoveryGate<String>()
        assertTrue(gate.claim("task",true,false))
        gate.reset()
        assertTrue(gate.claim("task",true,false))
    }
}

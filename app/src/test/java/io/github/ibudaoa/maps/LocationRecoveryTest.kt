package io.github.ibudaoa.maps

import io.github.ibudaoa.domain.CampusCoordinate
import org.junit.Assert.*
import org.junit.Test

class LocationRecoveryTest {
    private fun fix(at:Long)=DisplayFix(CampusCoordinate(30.0,114.0),8f,at,1_700_000_000_000+at)

    @Test fun `healthy two second updates keep the foreground session alive without client restarts`() {
        val recovery=LocationRecovery();recovery.begin(0)
        for(now in 0L..120_000L step 2_000L) {
            val sample=fix(now);recovery.accepted(sample,now)
            assertFalse(recovery.poll(now+1_000));assertSame(sample,recovery.current(now+1_000))
        }
        assertEquals(0,recovery.recoveryAttempts)
    }
    @Test fun `silent updates trigger recovery before expiry without clearing the still valid fix`() {
        val recovery=LocationRecovery();val sample=fix(0)
        recovery.begin(0);recovery.accepted(sample,0)
        assertFalse(recovery.poll(9_999));assertTrue(recovery.poll(10_000))
        assertSame(sample,recovery.current(10_000));assertFalse(recovery.poll(10_001))
        assertSame(sample,recovery.current(15_000));assertNull(recovery.current(15_001))
        assertEquals(0L,sample.receivedElapsedMillis)
    }
    @Test fun `a valid update during proactive recovery prevents a missing location interval`() {
        val recovery=LocationRecovery();recovery.begin(0);recovery.accepted(fix(0),0)
        assertTrue(recovery.poll(10_000))
        val updated=fix(12_000);recovery.accepted(updated,12_000)
        assertSame(updated,recovery.current(15_001));assertFalse(recovery.poll(21_999))
        assertEquals(0,recovery.recoveryAttempts);assertTrue(recovery.poll(22_000))
    }
    @Test fun `rejected or failed updates cannot remove a valid fix or renew its lifetime`() {
        val recovery=LocationRecovery();val sample=fix(0)
        recovery.begin(0);recovery.accepted(sample,0)
        for(now in listOf(1_000L,2_000L,4_000L,5_000L)) {
            recovery.failed(now);assertSame(sample,recovery.current(now))
        }
        assertFalse(recovery.poll(5_999));assertTrue(recovery.poll(6_000))
        recovery.failed(14_999)
        assertSame(sample,recovery.current(15_000));assertNull(recovery.current(15_001))
    }
    @Test fun `silent recoveries allow SDK timeout plus bounded five ten twenty thirty second backoff`() {
        val recovery=LocationRecovery();recovery.begin(0)
        for(now in listOf(10_000L,25_000L,45_000L,75_000L,115_000L,155_000L)) {
            assertFalse(recovery.poll(now-1));assertTrue(recovery.poll(now));assertFalse(recovery.poll(now))
        }
        assertEquals(6,recovery.recoveryAttempts);assertNull(recovery.current(155_000))
    }
    @Test fun `initialization failure retries automatically and later errors cannot postpone the deadline`() {
        val recovery=LocationRecovery();recovery.begin(0)
        recovery.failed(0);recovery.failed(4_000)
        assertFalse(recovery.poll(4_999));assertTrue(recovery.poll(5_000))
        recovery.failed(5_000);recovery.failed(9_999)
        assertTrue(recovery.poll(10_000))
    }
    @Test fun `manual recovery preserves the sample and success resets automatic backoff`() {
        val recovery=LocationRecovery();val sample=fix(0)
        recovery.begin(0);recovery.accepted(sample,0);recovery.recoverNow(2_000)
        assertSame(sample,recovery.current(2_000));assertEquals(1,recovery.recoveryAttempts)
        val updated=fix(3_000);recovery.accepted(updated,3_000)
        assertEquals(0,recovery.recoveryAttempts);assertFalse(recovery.poll(12_999));assertTrue(recovery.poll(13_000))
    }
    @Test fun `background or revoked permission stops recovery and returning requires a new sample`() {
        val recovery=LocationRecovery();recovery.begin(0);recovery.accepted(fix(0),0)
        recovery.stop();assertNull(recovery.current(1));assertFalse(recovery.poll(10_000))
        assertTrue(runCatching { recovery.accepted(fix(2),2) }.isFailure)
        recovery.begin(20_000);assertNull(recovery.current(20_000))
        assertFalse(recovery.poll(29_999));assertTrue(recovery.poll(30_000))
    }
    @Test fun `callbacks from a replaced client or previous foreground session lose ownership`() {
        val recovery=LocationRecovery();recovery.begin(0)
        val first=recovery.generation;assertTrue(recovery.owns(first))
        recovery.invalidateAttempt();val next=recovery.generation
        assertFalse(recovery.owns(first));assertTrue(recovery.owns(next))
        recovery.stop();assertFalse(recovery.owns(next))
        recovery.begin(1_000);assertFalse(recovery.owns(first));assertFalse(recovery.owns(next))
        assertTrue(recovery.owns(recovery.generation))
    }
    @Test fun `retention never turns a stale or future received time into a new location`() {
        val recovery=LocationRecovery();recovery.begin(0)
        assertTrue(runCatching { recovery.accepted(fix(0),15_001) }.isFailure)
        assertTrue(runCatching { recovery.accepted(fix(2_000),1_000) }.isFailure)
        recovery.accepted(fix(1_000),1_000);assertNull(recovery.current(999))
    }
}

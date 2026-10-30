package io.github.ibudaoa.running

import org.junit.Assert.*
import org.junit.Test

class RunTaskLedgerTest {
    private class Journal:RunTaskJournal {
        var bytes:ByteArray?=null;var archived:ByteArray?=null;var fail=false;var saves=0
        override fun load()=bytes?.clone()
        override fun archiveResolved(bytes:ByteArray) { if(fail) error("synthetic archive failure"); archived=bytes.clone() }
        override fun clear() { if(fail)error("synthetic removal failure");bytes?.fill(0);bytes=null }
        override fun save(bytes:ByteArray) { if(fail)error("synthetic storage failure");this.bytes=bytes.clone();saves++ }
    }
    private fun task(journal:Journal)=RunTaskLedger.create(journal,123,4,5,6,listOf("a","b"))
    private val ok="""{"status":1,"data":{}}""".toByteArray()
    private val registration="""{"status":1,"data":{"timestamp":1700000000,"str":"${"a".repeat(32)}","location_septal":20}}""".toByteArray()
    private val finish="""{"status":1,"data":{"record_id":45,"uid":123,"game_id":6,"start_time":1700000000,"record_status":1}}""".toByteArray()
    private val detail="""{"status":1,"data":{"id":45,"uid":123,"game_id":6,"start_time":1700000000,"type":1,"record_status":1}}""".toByteArray()
    private fun running(journal:Journal):RunTaskLedger {
        val task=task(journal)
        assertTrue(task.accept(task.begin(RunWrite.SELECT_ZONE),ok))
        assertTrue(task.accept(task.begin(RunWrite.REGISTER),registration))
        return task
    }
    @Test fun `durable intent precedes permission and duplicate begin or callback cannot repeat a write`() {
        val journal=Journal();val task=task(journal)
        val before=journal.saves;val permit=task.begin(RunWrite.SELECT_ZONE)
        assertEquals(before+1,journal.saves);assertTrue(task.summary.writePending)
        assertTrue(journal.bytes!!.toString(Charsets.UTF_8).contains("SELECT_ZONE"))
        assertTrue(runCatching { task.begin(RunWrite.SELECT_ZONE) }.isFailure)
        assertTrue(task.accept(permit,ok));assertFalse(task.accept(permit,ok))
        assertEquals(RunTaskPhase.ZONE_SELECTED,task.summary.phase)
    }
    @Test fun `failed intent persistence cannot grant a send or allow another command`() {
        val journal=Journal();val task=task(journal);journal.fail=true
        assertTrue(runCatching { task.begin(RunWrite.SELECT_ZONE) }.isFailure)
        assertEquals(RunTaskPhase.WRITE_UNCERTAIN,task.summary.phase)
        journal.fail=false
        assertTrue(runCatching { task.begin(RunWrite.SELECT_ZONE) }.isFailure)
        assertEquals(RunTaskPhase.INTERRUPTED,RunTaskLedger.restore(journal,123)!!.summary.phase)
    }
    @Test fun `network uncertainty survives restart and never retries a consumed start intent`() {
        val journal=Journal();val task=task(journal);val permit=task.begin(RunWrite.SELECT_ZONE)
        task.uncertain(permit)
        val restored=RunTaskLedger.restore(journal,123)!!;restored.foreground()
        assertEquals(RunTaskPhase.WRITE_UNCERTAIN,restored.summary.phase)
        assertTrue(restored.summary.needsReconciliation);assertTrue(restored.summary.writePending)
        assertTrue(runCatching { restored.begin(RunWrite.SELECT_ZONE) }.isFailure)
        assertFalse(task.accept(permit,ok))
    }
    @Test fun `process death with pending registration blocks replay without inventing a running task`() {
        val journal=Journal();val task=task(journal);task.accept(task.begin(RunWrite.SELECT_ZONE),ok)
        task.begin(RunWrite.REGISTER)
        val restored=RunTaskLedger.restore(journal,123)!!
        assertEquals(RunTaskPhase.WRITE_UNCERTAIN,restored.summary.phase)
        assertFalse(restored.summary.platformStarted)
        restored.foreground();assertTrue(runCatching { restored.resume() }.isFailure)
    }
    @Test fun `late registration after background saves the server pair but never starts local running`() {
        val journal=Journal();val task=task(journal);task.accept(task.begin(RunWrite.SELECT_ZONE),ok)
        val pending=task.begin(RunWrite.REGISTER);task.background()
        assertTrue(task.accept(pending,registration))
        assertEquals(RunTaskPhase.INTERRUPTED,task.summary.phase)
        assertTrue(task.summary.platformStarted);assertTrue(task.summary.needsReconciliation)
        assertFalse(task.summary.writePending)
        task.foreground();assertTrue(runCatching { task.begin(RunWrite.LOCATION) }.isFailure)
        assertTrue(runCatching { task.resume() }.isFailure)
    }
    @Test fun `background storage failure cannot be bypassed by returning foreground`() {
        val journal=Journal();val task=running(journal);journal.fail=true
        assertTrue(runCatching { task.background() }.isFailure);journal.fail=false;task.foreground()
        assertTrue(task.summary.needsReconciliation)
        assertTrue(runCatching { task.begin(RunWrite.LOCATION) }.isFailure)
    }
    @Test fun `pause is local preserves fixed points and emits no server pause write`() {
        val journal=Journal();val task=running(journal);val before=journal.saves
        task.pause();assertEquals(RunTaskPhase.PAUSED,task.summary.phase)
        assertFalse(task.summary.writePending);assertEquals(before+1,journal.saves)
        assertTrue(runCatching { task.begin(RunWrite.LOCATION) }.isFailure)
        task.resume();assertEquals(RunTaskPhase.RUNNING,task.summary.phase)
        val text=journal.bytes!!.toString(Charsets.UTF_8)
        assertTrue(text.contains("\"points\":[\"a\",\"b\"]"))
        assertFalse(text.contains("latitude"));assertFalse(text.contains("longitude"))
    }
    @Test fun `location status one never marks checkpoints or final success`() {
        val journal=Journal();val task=running(journal)
        assertTrue(task.accept(task.begin(RunWrite.LOCATION),ok))
        assertEquals(RunTaskPhase.RUNNING,task.summary.phase)
        assertFalse(task.summary.recordVerified);assertFalse(task.summary.recordBound)
    }
    @Test fun `finish acknowledgement only binds a record and exact detail establishes validity`() {
        val journal=Journal();val task=running(journal)
        assertTrue(task.accept(task.begin(RunWrite.FINISH),finish))
        assertEquals(RunTaskPhase.AWAITING_VERDICT,task.summary.phase)
        assertTrue(task.summary.recordBound);assertFalse(task.summary.recordVerified)
        assertEquals(RunRecordVerdict.MISMATCH,task.verifyRecord(detail.toString(Charsets.UTF_8).replace("\"id\":45","\"id\":46").toByteArray()).verdict)
        assertEquals(RunTaskPhase.AWAITING_VERDICT,task.summary.phase)
        assertTrue(task.verifyRecord(detail).platformValid)
        assertEquals(RunTaskPhase.VALID,task.summary.phase)
        assertTrue(task.summary.recordVerified)
        assertFalse(task.toString().contains("123"));assertFalse(task.summary.toString().contains("1700000000"))
    }
    @Test fun `finish response must belong to this exact account zone and registered start`() {
        for(change in listOf("\"uid\":123" to "\"uid\":124","\"game_id\":6" to "\"game_id\":7","1700000000" to "1700000001")) {
            val task=running(Journal());val permit=task.begin(RunWrite.FINISH)
            assertFalse(task.accept(permit,finish.toString(Charsets.UTF_8).replace(change.first,change.second).toByteArray()))
            assertEquals(RunTaskPhase.WRITE_UNCERTAIN,task.summary.phase)
            assertFalse(task.summary.recordBound)
        }
    }
    @Test fun `received response save can be retried without repeating the network write`() {
        val journal=Journal();val task=task(journal);val permit=task.begin(RunWrite.SELECT_ZONE)
        journal.fail=true;assertTrue(runCatching { task.accept(permit,ok) }.isFailure)
        journal.fail=false;assertTrue(task.accept(permit,ok))
        assertEquals(RunTaskPhase.ZONE_SELECTED,task.summary.phase)
        assertFalse(task.accept(permit,ok))
    }
    @Test fun `existing run cannot be replaced by another task or account`() {
        val journal=Journal();running(journal);val saved=journal.bytes!!.clone()
        assertTrue(runCatching { RunTaskLedger.create(journal,456,4,5,6,listOf("x","y")) }.isFailure)
        assertArrayEquals(saved,journal.bytes)
        val restored=RunTaskLedger.restore(journal,123)!!
        assertEquals(RunTaskPhase.INTERRUPTED,restored.summary.phase)
        restored.foreground();assertTrue(runCatching { restored.begin(RunWrite.FINISH) }.isFailure)
    }
    @Test fun `pending verdict can be queried after restart without repeating finish`() {
        val journal=Journal();val task=running(journal);task.accept(task.begin(RunWrite.FINISH),finish)
        val restored=RunTaskLedger.restore(journal,123)!!
        assertEquals(RunTaskPhase.AWAITING_VERDICT,restored.summary.phase)
        assertTrue(runCatching { restored.verifyRecord(detail) }.isFailure)
        restored.foreground();assertTrue(restored.verifyRecord(detail).platformValid)
        assertTrue(runCatching { restored.begin(RunWrite.FINISH) }.isFailure)
    }
    @Test fun `failed malformed or lost response never authorizes the next stage`() {
        for(response in listOf("{}","not json","""{"status":0,"data":{}}""")) {
            val task=task(Journal());assertFalse(task.accept(task.begin(RunWrite.SELECT_ZONE),response.toByteArray()))
            assertEquals(RunTaskPhase.WRITE_UNCERTAIN,task.summary.phase)
            assertTrue(runCatching { task.begin(RunWrite.REGISTER) }.isFailure)
        }
    }
    @Test fun `unresolved journals cannot be erased and only verified results can be released`() {
        val journal=Journal();val task=running(journal)
        assertTrue(runCatching { task.releaseResolved() }.isFailure)
        task.accept(task.begin(RunWrite.FINISH),finish)
        assertTrue(runCatching { task.releaseResolved() }.isFailure)
        task.verifyRecord(detail);journal.fail=true
        assertTrue(runCatching { task.releaseResolved() }.isFailure)
        assertTrue(journal.bytes!=null);assertNull(journal.archived);journal.fail=false
        task.archiveResolved();assertNotNull(journal.bytes);assertNotNull(journal.archived)
        task.releaseResolved();assertNull(journal.bytes);assertNotNull(journal.archived);assertFalse(journal.archived!!.toString(Charsets.UTF_8).contains("proof"))
        assertEquals(RunTaskPhase.PREPARED,task(journal).summary.phase)
    }
    @Test fun `restoration for a different account never rebinds or removes the pending task`() {
        val journal=Journal();running(journal);val saved=journal.bytes!!.clone()
        assertTrue(runCatching { RunTaskLedger.restore(journal,456) }.isFailure)
        assertArrayEquals(saved,journal.bytes)
    }

    @Test fun `official zone array and location empty data acknowledge only their exact writes`() {
        val task=task(Journal())
        assertTrue(task.accept(task.begin(RunWrite.SELECT_ZONE),"""{"status":1,"data":[{"id":6}]}""".toByteArray()))
        assertTrue(task.accept(task.begin(RunWrite.REGISTER),registration))
        assertTrue(task.accept(task.begin(RunWrite.LOCATION),"""{"status":1,"data":null}""".toByteArray()))
        assertEquals(RunTaskPhase.RUNNING,task.summary.phase)
        assertFalse(task.summary.recordVerified)
    }

    @Test fun `invalid record retains safe platform reason across restart without exposing it in summary`() {
        val journal=Journal();val task=running(journal);task.accept(task.begin(RunWrite.FINISH),finish)
        val rejected=detail.toString(Charsets.UTF_8).replace("\"record_status\":1","\"record_status\":2,\"record_failed_reason\":\"打卡次数不足\"")
        assertEquals(RunRecordVerdict.INVALID,task.verifyRecord(rejected.toByteArray()).verdict)
        val restored=RunTaskLedger.restore(journal,123)!!
        assertEquals("打卡次数不足",restored.failureReasonForDisplay())
        assertFalse(restored.summary.toString().contains("打卡"))
    }

}

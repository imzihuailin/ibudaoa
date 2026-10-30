package io.github.ibudaoa.running

import io.github.ibudaoa.domain.*
import io.github.ibudaoa.maps.DisplayFix
import kotlinx.coroutines.*
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class RunExecutionControllerTest {
    private class Journal:RunTaskJournal {
        var bytes:ByteArray?=null
        override fun load()=bytes?.clone()
        override fun save(bytes:ByteArray) { this.bytes=bytes.clone() }
        override fun clear() { bytes=null }
        override fun archiveResolved(bytes:ByteArray) {}
    }
    private val metric=object:PlatformDistance {
        override val implementationId="synthetic-flat"
        override fun meters(from:CampusCoordinate,to:CampusCoordinate)=hypot(to.latitude-from.latitude,to.longitude-from.longitude)*100_000
    }
    private fun fix(x:Double,t:Long)=DisplayFix(CampusCoordinate(0.0,x/100_000),3f,t,1_700_000_000_000+t)
    private inner class Harness {
        var time=1000L;var location=fix(0.0,time);var account:Long?=123;var prepared=true
        val journal=Journal();val writes=mutableListOf<RunWrite>();val sentFixes=mutableListOf<DisplayFix>()
        var duringWrite:suspend (RunWrite)->Unit={};var failure:RunWrite?=null;var queries=0
        val ledger=RunTaskLedger.create(journal,123,4,5,6,listOf("a","b"))
        val progress=ForegroundRunProgress(mapOf("a" to CampusCoordinate(0.0,0.0002),"b" to CampusCoordinate(0.0,0.0004)),5.0,metric)
        val gateway=object:RunExecutionGateway {
            override suspend fun write(operation:RunWrite,context:RunRequestContext,fix:DisplayFix?,progress:RunProgressSummary,trace:List<MeasuredRunFix>,checkpoints:List<ObservedRunCheckpoint>):ByteArray {
                assertTrue(ledger.summary.writePending);writes.add(operation)
                assertEquals(listOf("a","b"),context.points)
                fix?.let(sentFixes::add);duringWrite(operation)
                if(failure==operation)error("synthetic unknown network outcome")
                return when(operation) {
                    RunWrite.REGISTER -> """{"status":1,"data":{"timestamp":1700000000,"str":"${"a".repeat(32)}","location_septal":20}}"""
                    RunWrite.FINISH -> {
                        assertTrue(trace.isNotEmpty());assertEquals("a".repeat(32),context.registrationProof)
                        assertEquals(listOf("a","b"),checkpoints.map { it.id })
                        assertTrue(checkpoints.all { it.fix.sdkTimeMillis!=null })
                        """{"status":1,"data":{"record_id":45,"uid":123,"game_id":6,"start_time":1700000000,"record_status":1}}"""
                    }
                    else -> """{"status":1,"data":{}}"""
                }.toByteArray()
            }
            override suspend fun record(expected:ExpectedRunRecord):ByteArray {
                queries++;assertEquals(45L,expected.recordId)
                return """{"status":1,"data":{"id":45,"uid":123,"game_id":6,"start_time":1700000000,"type":1,"record_status":1}}""".toByteArray()
            }
        }
        val core=RunExecutionController(ledger,progress,gateway,{account},{prepared},{location},{time})
        fun move(x:Double,t:Long) { time=t;location=fix(x,t);core.sample(location) }
    }
    @Test fun `full command chain uses actual fixes and independently verifies the exact finished record`() = runBlocking {
        val h=Harness();assertTrue(h.core.start());assertFalse(h.core.start())
        h.move(20.0,6000);assertTrue(h.core.uploadLocation());assertTrue(h.core.pause())
        h.time=8000;h.location=fix(20.0,8000);assertTrue(h.core.resume())
        h.move(40.0,13000);assertTrue(h.core.uploadLocation());assertTrue(h.core.finish())
        assertEquals(40.0,h.core.summary.progress.distance.meters,0.0001)
        assertEquals(2,h.core.summary.progress.observedPoints)
        assertEquals(0,h.core.summary.progress.platformConfirmedPoints)
        assertFalse(h.core.summary.task.recordVerified);assertTrue(h.core.verifyRecord())
        assertEquals(RunTaskPhase.VALID,h.core.summary.task.phase)
        assertEquals(listOf(RunWrite.SELECT_ZONE,RunWrite.REGISTER,RunWrite.LOCATION,RunWrite.LOCATION,RunWrite.FINISH),h.writes)
        assertEquals(1,h.queries)
    }
    @Test fun `concurrent start clicks cannot issue another select or registration`() = runBlocking {
        val h=Harness();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        h.duringWrite={ if(it==RunWrite.SELECT_ZONE) { entered.complete(Unit);release.await() } }
        val start=async { h.core.start() };entered.await()
        assertFalse(h.core.start());release.complete(Unit);assertTrue(start.await())
        assertEquals(listOf(RunWrite.SELECT_ZONE,RunWrite.REGISTER),h.writes)
    }
    @Test fun `background during registration retains server acknowledgement and blocks subsequent movement`() = runBlocking {
        val h=Harness();h.duringWrite={ if(it==RunWrite.REGISTER)h.core.background() }
        assertFalse(h.core.start());assertTrue(h.core.summary.task.platformStarted)
        assertEquals(RunTaskPhase.INTERRUPTED,h.core.summary.task.phase)
        h.core.foreground();assertFalse(h.core.resume());h.move(40.0,6000)
        assertEquals(0.0,h.core.summary.progress.distance.meters,0.0)
        assertFalse(h.core.uploadLocation());assertFalse(h.core.start())
    }
    @Test fun `account replacement during request never continues the old task under the new account`() = runBlocking {
        val h=Harness();h.duringWrite={ if(it==RunWrite.SELECT_ZONE)h.account=456 }
        assertFalse(h.core.start());assertEquals(listOf(RunWrite.SELECT_ZONE),h.writes)
        assertTrue(h.core.summary.task.needsReconciliation)
        assertFalse(h.core.finish());assertFalse(h.core.uploadLocation())
    }
    @Test fun `unknown registration outcome stops local progress and is never retried`() = runBlocking {
        val h=Harness();h.failure=RunWrite.REGISTER
        assertFalse(h.core.start());assertFalse(h.core.start());assertFalse(h.core.resume())
        assertEquals(RunTaskPhase.WRITE_UNCERTAIN,h.core.summary.task.phase)
        assertEquals(1,h.writes.count { it==RunWrite.REGISTER })
        assertEquals(0.0,h.core.summary.progress.distance.meters,0.0)
    }
    @Test fun `GPS jump rejected by measurement is not forwarded as a location upload`() = runBlocking {
        val h=Harness();assertTrue(h.core.start());h.move(1000.0,2000)
        assertTrue(h.core.uploadLocation())
        assertEquals(0.0,h.sentFixes.single().coordinate.longitude,0.0)
        assertEquals(1,h.progress.traceSnapshot().size)
        assertEquals(0,h.core.summary.progress.observedPoints)
    }
    @Test fun `expired preparation or absent fresh GPS cannot create a platform task`() = runBlocking {
        val h=Harness();h.prepared=false;assertFalse(h.core.start());assertTrue(h.writes.isEmpty())
        h.prepared=true;h.time=20000;assertFalse(h.core.start());assertTrue(h.writes.isEmpty())
    }
    @Test fun `cancellation after dispatch preserves uncertain intent and prevents replay`() = runBlocking {
        val h=Harness();val entered=CompletableDeferred<Unit>()
        h.duringWrite={ if(it==RunWrite.REGISTER) { entered.complete(Unit);awaitCancellation() } }
        val job=launch { h.core.start() };entered.await();job.cancelAndJoin()
        assertEquals(RunTaskPhase.WRITE_UNCERTAIN,h.core.summary.task.phase)
        assertFalse(h.core.start());assertEquals(1,h.writes.count { it==RunWrite.REGISTER })
    }
}

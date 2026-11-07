package io.github.ibudaoa.capture

import io.github.ibudaoa.running.ExpectedRunRecord
import io.github.ibudaoa.running.UploadFixture
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RunRecordReaderTest {
    @Test fun `reconnecting another account cannot send a query for a retained task`()=runBlocking {
        val session=RunProtocolFixture.session()
        val context=io.github.ibudaoa.running.RunRequestContext(43,1,2,3,listOf("21","22"),1700000000,"a".repeat(32))
        var sent=false;var closed=false
        val reader=RunRecordReader(session,{Instant.ofEpochSecond(RunProtocolFixture.now)},{object:AccountReadConnection {
            override fun send(request:LocalRequest):ByteArray { sent=true;error("must not send") }
            override fun close() { closed=true }
        }})
        assertTrue(runCatching { reader.record(context,ExpectedRunRecord(99,43,3,1700000000)) }.isFailure)
        assertFalse(sent);assertTrue(closed)
    }
    @Test fun `verification cancellation closes a blocked read and permits a later query`()=runBlocking {
        val entered=CountDownLatch(1)
        val closed=CountDownLatch(1)
        val session=RunProtocolFixture.session()
        val expected=ExpectedRunRecord(99,42,3,1700000000)
        val blocked=object:AccountReadConnection {
            override fun send(request:LocalRequest):ByteArray {
                assertEquals(RunEndpoint.RECORD.path,request.target)
                entered.countDown()
                check(closed.await(5,TimeUnit.SECONDS)) { "Read was not cancelled" }
                error("Closed read")
            }
            override fun close() { closed.countDown() }
        }
        val reader=RunRecordReader(session,{Instant.ofEpochSecond(RunProtocolFixture.now)},{blocked})
        val query=launch(Dispatchers.Default) { reader.record(UploadFixture.context,expected) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(5,TimeUnit.SECONDS) })
        withTimeout(2000) { query.cancelAndJoin() }
        assertEquals(0L,closed.count)
        var queries=0
        val next=RunRecordReader(session,{Instant.ofEpochSecond(RunProtocolFixture.now)},{object:AccountReadConnection {
            override fun send(request:LocalRequest):ByteArray { queries++;return byteArrayOf(1) }
            override fun close() {}
        }})
        assertArrayEquals(byteArrayOf(1),next.record(UploadFixture.context,expected))
        assertEquals(1,queries)
    }
}

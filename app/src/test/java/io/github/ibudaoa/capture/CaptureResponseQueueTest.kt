package io.github.ibudaoa.capture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CaptureResponseQueueTest {
    @Test fun `received responses drain in order after transport has disconnected and wipe copies`() {
        val queue=CaptureResponseQueue()
        val entered=CountDownLatch(1);val proceed=CountDownLatch(1)
        val events=java.util.Collections.synchronizedList(mutableListOf<Int>())
        val first=byteArrayOf(1);val second=byteArrayOf(2)
        try {
            assertTrue(queue.submit({ entered.countDown();assertTrue(proceed.await(2,TimeUnit.SECONDS));events.add(first[0].toInt()) }, { first.fill(0) }))
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            assertTrue(queue.submit({events.add(second[0].toInt())},{second.fill(0)}))
            proceed.countDown()
            assertTrue(queue.drain(2))
            assertEquals(listOf(1,2),events)
            assertArrayEquals(byteArrayOf(0),first);assertArrayEquals(byteArrayOf(0),second)
            val rejected=byteArrayOf(3)
            assertFalse(queue.submit({fail("closed queue accepted response")},{rejected.fill(0)}))
            assertEquals(0,rejected[0].toInt())
        } finally { proceed.countDown();queue.cancel() }
    }
    @Test fun `explicit cancellation discards queued responses and wipes buffers`() {
        val queue=CaptureResponseQueue();val entered=CountDownLatch(1);val block=CountDownLatch(1)
        val wiped=CountDownLatch(1);val pending=byteArrayOf(5)
        try {
            queue.submit({entered.countDown();try { block.await() } catch (_:InterruptedException) {}},{wiped.countDown()})
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            queue.submit({fail("cancelled response processed")},{pending.fill(0)})
            queue.cancel()
            assertTrue(wiped.await(2,TimeUnit.SECONDS))
            assertEquals(0,pending[0].toInt())
        } finally {block.countDown();queue.cancel()}
    }
    @Test fun `slow response drain is bounded without pretending processing succeeded`() {
        val queue=CaptureResponseQueue();val block=CountDownLatch(1)
        try {
            queue.submit({try {block.await()}catch(_:InterruptedException){}},{})
            assertFalse(queue.drain(0))
        } finally {block.countDown();queue.cancel()}
    }
}

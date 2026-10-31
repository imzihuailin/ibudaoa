package io.github.ibudaoa.capture

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Received responses survive transport disconnect; cancellation wipes queued private buffers. */
internal class CaptureResponseQueue {
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    private class Work(val action: () -> Unit, val wipe: () -> Unit) : Runnable {
        private val done = AtomicBoolean(false)
        override fun run() { if (!done.compareAndSet(false, true)) return; try { action() } finally { wipe() } }
        fun discard() { if (done.compareAndSet(false, true)) wipe() }
    }
    fun submit(action: () -> Unit, wipe: () -> Unit): Boolean {
        val task = Work(action, wipe)
        return try { worker.execute(task); true } catch (_: Exception) { task.discard(); false }
    }
    fun drain(timeoutSeconds: Long = 25): Boolean {
        worker.shutdown()
        return worker.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)
    }
    fun cancel() { worker.shutdownNow().forEach { (it as Work).discard() } }
}

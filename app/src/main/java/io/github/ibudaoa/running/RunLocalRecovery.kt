package io.github.ibudaoa.running

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Caller must first stop the old executor. Keep the journal until releasing its lock succeeds. */
internal suspend fun discardInterruptedLocal(ledger:RunTaskLedger,releasePointLock:suspend ()->Unit) {
    check(ledger.canDiscardUnverifiable())
    withContext(NonCancellable) {
        releasePointLock()
        withContext(Dispatchers.IO) { ledger.discardUnverifiable() }
    }
}

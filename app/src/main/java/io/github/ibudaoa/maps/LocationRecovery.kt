package io.github.ibudaoa.maps

/** Monotonic, memory-only recovery policy. Rebuilding an SDK client never renews a fix's timestamp. */
internal class LocationRecovery {
    var active=false
        private set
    var recoveryAttempts=0
        private set
    var generation=0L
        private set
    private var last:DisplayFix?=null
    private var nextRecovery:Long?=null

    fun begin(now:Long) {
        require(now>=0)
        invalidateAttempt();active=true;last=null;recoveryAttempts=0;nextRecovery=now+REFRESH_AFTER_MS
    }
    fun invalidateAttempt() { generation++ }
    fun owns(value:Long):Boolean = active && generation==value
    fun stop() { invalidateAttempt();active=false;last=null;nextRecovery=null;recoveryAttempts=0 }
    fun accepted(fix:DisplayFix,now:Long) {
        check(active)
        require(now>=fix.receivedElapsedMillis && fix.receivedElapsedMillis>=0 &&
            now-fix.receivedElapsedMillis<=ForegroundFixGate.MAX_AGE_MS)
        last=fix;recoveryAttempts=0;nextRecovery=fix.receivedElapsedMillis+REFRESH_AFTER_MS
    }
    fun current(now:Long):DisplayFix? = last?.takeIf {
        active && now>=it.receivedElapsedMillis && now-it.receivedElapsedMillis<=ForegroundFixGate.MAX_AGE_MS
    }
    /** Repeated failures must not push a pending recovery further into the future. */
    fun failed(now:Long) {
        if(active)nextRecovery=minOf(nextRecovery ?: Long.MAX_VALUE,now+delay((recoveryAttempts-1).coerceAtLeast(0)))
    }
    fun poll(now:Long):Boolean {
        if(!active || nextRecovery?.let { now>=it }!=true)return false
        recoverNow(now);return true
    }
    fun recoverNow(now:Long) {
        check(active)
        // Let the new SDK request use its 10-second timeout before applying failure backoff.
        nextRecovery=now+REFRESH_AFTER_MS+delay(recoveryAttempts)
        recoveryAttempts=(recoveryAttempts+1).coerceAtMost(1_000_000)
    }
    private fun delay(index:Int)=longArrayOf(5_000L,10_000L,20_000L,30_000L)[index.coerceAtMost(3)]
    companion object { const val REFRESH_AFTER_MS=10_000L }
}

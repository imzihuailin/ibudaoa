package io.github.ibudaoa.running

/** Monotonic foreground time, shared by polling and the manual-action guard. */
internal class RunVerificationWindow {
    private var elapsed=0L
    private var resumedAt:Long?=null
    fun resume(now:Long) { if(resumedAt==null)resumedAt=now }
    fun pause(now:Long) {
        resumedAt?.let { elapsed+=(now-it).coerceAtLeast(0) }
        resumedAt=null
    }
    fun remaining(now:Long):Long = (60_000L-elapsed-(resumedAt?.let { (now-it).coerceAtLeast(0) } ?: 0L)).coerceAtLeast(0)
    fun state(now:Long):AutomaticVerificationState =
        if(remaining(now)==0L)AutomaticVerificationState.MANUAL else AutomaticVerificationState.VERIFYING
}

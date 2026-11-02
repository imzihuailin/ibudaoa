package io.github.ibudaoa.running

/** Claims one attempt for each ready dependency snapshot. Waiting never consumes an attempt. */
internal class RunRecoveryGate<T> {
    private var attempted:T?=null
    fun claim(key:T, ready:Boolean, busy:Boolean):Boolean {
        if(!ready || busy || attempted==key)return false
        attempted=key
        return true
    }
    fun reset() { attempted=null }
}

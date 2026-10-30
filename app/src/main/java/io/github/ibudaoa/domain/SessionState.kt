package io.github.ibudaoa.domain

/** Platform-confirmed states are distinct from local interruption. No synthetic progress. */
enum class SessionPhase {
    IDLE, PREPARING, RUNNING, PAUSED, INTERRUPTED, AWAITING_VERDICT, VALID, INVALID, QUERY_FAILED,
}

data class SessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val recordId: String? = null,
    val selectedPointIds: List<String> = emptyList(),
    val needsReconciliation: Boolean = false,
) {
    val keepScreenOn: Boolean get() = phase in setOf(SessionPhase.PREPARING,SessionPhase.RUNNING,SessionPhase.PAUSED,SessionPhase.AWAITING_VERDICT)

    fun onForegroundLost(): SessionState = when (phase) {
        SessionPhase.PREPARING, SessionPhase.RUNNING, SessionPhase.PAUSED ->
            copy(phase = SessionPhase.INTERRUPTED, needsReconciliation = true)
        else -> this
    }

    // Returning to the activity never restarts a platform task.
    fun onForegroundReturned(): SessionState = this
}

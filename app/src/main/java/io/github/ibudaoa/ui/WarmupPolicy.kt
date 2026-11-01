package io.github.ibudaoa.ui

import io.github.ibudaoa.AppState
import io.github.ibudaoa.capture.AccountSessionPhase

internal enum class WarmupReason { FIRST_SETUP, LOGIN, LOCATION }

/** Loading and recoverable checks never request a dialog. No network or lifecycle side effects. */
internal object WarmupPolicy {
    fun ready(state: AppState): Boolean = state.loaded && !state.preparationStorageFailed && state.accountStorageLoaded && !state.locationStartupPending

    fun needsLogin(state: AppState): Boolean = state.accountSession.phase == AccountSessionPhase.RECONNECT_REQUIRED ||
        state.preparationCompleted && state.accountSession.phase in setOf(
            AccountSessionPhase.EMPTY, AccountSessionPhase.CLEARED, AccountSessionPhase.FAILED)

    fun reason(state: AppState): WarmupReason? = when {
        !ready(state) || state.capture.active -> null
        !state.preparationCompleted -> WarmupReason.FIRST_SETUP
        needsLogin(state) -> WarmupReason.LOGIN
        !state.locationGranted || !state.mapConsent -> WarmupReason.LOCATION
        else -> null
    }

    fun recovered(state: AppState): Boolean = ready(state) && state.preparationCompleted &&
        state.locationGranted && state.mapConsent && state.accountSession.phase == AccountSessionPhase.READY
}

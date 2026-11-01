package io.github.ibudaoa.capture

internal enum class AccountRecovery { REUSE, REQUIRE_CAPTURE, RETRY_READ, CANCELLED }
internal object AccountRecoveryPolicy {
    fun decide(account:AccountSessionSnapshot,verified:Boolean,cancelled:Boolean=false):AccountRecovery=when {
        cancelled -> AccountRecovery.CANCELLED
        verified -> AccountRecovery.REUSE
        account.phase==AccountSessionPhase.STORAGE_ERROR || account.refreshOutcome in setOf(AccountRefreshOutcome.STORAGE_FAILED,
            AccountRefreshOutcome.IN_PROGRESS,AccountRefreshOutcome.UNCONFIRMED) -> AccountRecovery.RETRY_READ
        account.phase in setOf(AccountSessionPhase.EMPTY,AccountSessionPhase.CLEARED,AccountSessionPhase.FAILED) -> AccountRecovery.REQUIRE_CAPTURE
        account.phase==AccountSessionPhase.RECONNECT_REQUIRED -> AccountRecovery.REQUIRE_CAPTURE
        !account.protocolReady && account.available -> AccountRecovery.REQUIRE_CAPTURE
        else -> AccountRecovery.RETRY_READ
    }
}

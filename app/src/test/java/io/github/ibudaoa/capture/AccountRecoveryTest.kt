package io.github.ibudaoa.capture

import org.junit.Assert.*
import org.junit.Test

class AccountRecoveryTest {
    @Test fun `network HTTP and unknown school failures preserve direct recovery instead of VPN`() {
        for(outcome in listOf(AccountReadOutcome.NETWORK_ERROR,AccountReadOutcome.HTTP_ERROR,AccountReadOutcome.INVALID_RESPONSE,AccountReadOutcome.PASSED)) {
            val account=AccountSessionSnapshot(phase=AccountSessionPhase.RETRYABLE,credentialsPersisted=true,protocolReady=true,
                lastRead=AccountReadResult(outcome),preparation=LivePreparationSnapshot(LivePreparationPhase.FAILED))
            assertEquals(AccountRecovery.RETRY_READ,AccountRecoveryPolicy.decide(account,false))
        }
    }
    @Test fun `only missing unusable or confirmed reauthentication requires fresh capture`() {
        assertEquals(AccountRecovery.REQUIRE_CAPTURE,AccountRecoveryPolicy.decide(AccountSessionSnapshot(),false))
        assertEquals(AccountRecovery.REQUIRE_CAPTURE,AccountRecoveryPolicy.decide(AccountSessionSnapshot(phase=AccountSessionPhase.RECONNECT_REQUIRED),false))
        assertEquals(AccountRecovery.REQUIRE_CAPTURE,AccountRecoveryPolicy.decide(AccountSessionSnapshot(phase=AccountSessionPhase.RESTORED,protocolReady=false),false))
        assertEquals(AccountRecovery.RETRY_READ,AccountRecoveryPolicy.decide(AccountSessionSnapshot(phase=AccountSessionPhase.STORAGE_ERROR),false))
        assertEquals(AccountRecovery.RETRY_READ,AccountRecoveryPolicy.decide(AccountSessionSnapshot(phase=AccountSessionPhase.RECONNECT_REQUIRED,refreshOutcome=AccountRefreshOutcome.UNCONFIRMED),false))
    }
    @Test fun `verified recovery skips VPN while cancellation wins over a late success`() {
        assertEquals(AccountRecovery.REUSE,AccountRecoveryPolicy.decide(AccountSessionSnapshot(),true))
        assertEquals(AccountRecovery.CANCELLED,AccountRecoveryPolicy.decide(AccountSessionSnapshot(),true,true))
    }
}

package io.github.ibudaoa.ui

import io.github.ibudaoa.AppState
import io.github.ibudaoa.capture.*
import org.junit.Assert.*
import org.junit.Test

class WarmupPolicyTest {
    private val configured = AppState(loaded = true, accountStorageLoaded = true,
        locationStartupPending = false, preparationCompleted = true, capturedConfig = true,
        locationGranted = true, mapConsent = true,
        accountSession = AccountSessionSnapshot(phase = AccountSessionPhase.RESTORED, credentialsPersisted = true))

    @Test fun `loading orders cannot flash setup or login dialogs`() {
        for (state in listOf(AppState(), configured.copy(loaded = false),
            configured.copy(accountStorageLoaded = false, accountSession = AccountSessionSnapshot()),
            configured.copy(locationStartupPending = true, locationGranted = false),
            configured.copy(preparationStorageFailed = true, preparationCompleted = false))) {
            assertNull(WarmupPolicy.reason(state))
        }
    }

    @Test fun `first incomplete setup opens only after startup checks finish`() {
        assertEquals(WarmupReason.FIRST_SETUP, WarmupPolicy.reason(configured.copy(preparationCompleted = false)))
        assertNull(WarmupPolicy.reason(configured.copy(preparationCompleted = false, loaded = false)))
    }

    @Test fun `cached installation and transient account checks stay silent without ready roads`() {
        for (phase in listOf(AccountSessionPhase.RESTORED, AccountSessionPhase.READY, AccountSessionPhase.CHECKING,
            AccountSessionPhase.SUSPENDED, AccountSessionPhase.RETRYABLE, AccountSessionPhase.STORAGE_ERROR)) {
            assertNull(WarmupPolicy.reason(configured.copy(accountSession = AccountSessionSnapshot(phase = phase))))
        }
        assertNull(configured.roadReport)
        assertNull(WarmupPolicy.reason(configured.copy(busy = true, preparationError = "暂时网络失败")))
    }

    @Test fun `only missing login or permissions require intervention`() {
        assertEquals(WarmupReason.LOGIN, WarmupPolicy.reason(configured.copy(accountSession =
            AccountSessionSnapshot(phase = AccountSessionPhase.RECONNECT_REQUIRED))))
        assertEquals(WarmupReason.LOGIN, WarmupPolicy.reason(configured.copy(accountSession = AccountSessionSnapshot())))
        assertEquals(WarmupReason.LOCATION, WarmupPolicy.reason(configured.copy(locationGranted = false)))
        assertEquals(WarmupReason.LOCATION, WarmupPolicy.reason(configured.copy(mapConsent = false)))
    }

    @Test fun `active capture does not reopen a dismissed dialog and recovery clears the episode`() {
        assertNull(WarmupPolicy.reason(configured.copy(preparationCompleted = false,
            capture = CaptureState(phase = CapturePhase.LISTENING))))
        assertFalse(WarmupPolicy.recovered(configured))
        assertTrue(WarmupPolicy.recovered(configured.copy(accountSession = AccountSessionSnapshot(phase = AccountSessionPhase.READY))))
    }
}

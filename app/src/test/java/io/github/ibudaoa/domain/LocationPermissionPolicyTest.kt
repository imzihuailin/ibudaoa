package io.github.ibudaoa.domain

import org.junit.Assert.*
import org.junit.Test

class LocationPermissionPolicyTest {
    @Test fun `first request does not require a rationale`() {
        assertTrue(LocationPermissionPolicy.canRequest(false, false, false, false))
    }
    @Test fun `ordinary denial still permits a system request`() {
        assertTrue(LocationPermissionPolicy.canRequest(true, true, false, false))
    }
    @Test fun `fixed denial requires settings`() {
        assertFalse(LocationPermissionPolicy.canRequest(true, false, false, false))
    }
    @Test fun `approximate access permits one precise upgrade without rationale then settings`() {
        assertTrue(LocationPermissionPolicy.canRequest(true, false, true, false))
        assertFalse(LocationPermissionPolicy.canRequest(true, false, true, true))
        assertTrue(LocationPermissionPolicy.canRequest(true, true, true, true))
    }
}

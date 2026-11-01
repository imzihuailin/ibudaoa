package io.github.ibudaoa.domain

/** Approximate access can be upgraded once even when the fine-permission rationale is absent. */
object LocationPermissionPolicy {
    fun canRequest(asked: Boolean, rationale: Boolean, coarseGranted: Boolean, upgradeAsked: Boolean): Boolean =
        !asked || rationale || (coarseGranted && !upgradeAsked)
}

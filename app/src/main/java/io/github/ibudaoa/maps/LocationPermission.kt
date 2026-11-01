package io.github.ibudaoa.maps

import androidx.compose.runtime.staticCompositionLocalOf

/** One Activity owns permission requests for both setup and map controls. */
data class LocationPermissionAccess(val revision: Int = 0, val canRequestAutomatically: Boolean = true, val request: () -> Unit = {})
val LocalLocationPermission = staticCompositionLocalOf { LocationPermissionAccess() }

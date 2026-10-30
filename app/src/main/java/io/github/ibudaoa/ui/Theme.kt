package io.github.ibudaoa.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val LightColors = lightColorScheme(
    primary = Color(0xFF0D6047),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E9CF),
    onPrimaryContainer = Color(0xFF073C2D),
    secondaryContainer = Color(0xFFDDEED9),
    background = Color(0xFFF0F7EE),
    onBackground = Color(0xFF092F28),
    surface = Color(0xFFF0F7EE),
    surfaceContainer = Color(0xFFE5F1E1),
    onSurface = Color(0xFF092F28),
    onSurfaceVariant = Color(0xFF4F665B),
    outlineVariant = Color(0xFFC7D8C9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8ED6B3),
    onPrimary = Color(0xFF003829),
    primaryContainer = Color(0xFF17513E),
    onPrimaryContainer = Color(0xFFD0F3DD),
    secondaryContainer = Color(0xFF304B3A),
    background = Color(0xFF101D17),
    surface = Color(0xFF101D17),
    surfaceContainer = Color(0xFF1A2B21),
    onBackground = Color(0xFFE0EBDD),
    onSurface = Color(0xFFE0EBDD),
    onSurfaceVariant = Color(0xFFB5CABB),
    outlineVariant = Color(0xFF405548),
)

@Composable
fun IbudaoaTheme(content: @Composable () -> Unit) {
    // This app uses the regular typography scale, independent of the system font size.
    val density = LocalDensity.current.density
    CompositionLocalProvider(LocalDensity provides Density(density = density, fontScale = 1f)) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
    }
}

package com.topognss.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.topognss.app.gnss.ConnectionStatus
import com.topognss.app.gnss.GnssState
import com.topognss.app.gnss.SolutionType

// Colores de estado de solución (alto contraste para uso a pleno sol)
val FixGreen = Color(0xFF1FA34A)
val FloatYellow = Color(0xFFF2B705)
val DgpsOrange = Color(0xFFF07F13)
val SingleRed = Color(0xFFD7263D)
val OfflineGrey = Color(0xFF6B7280)

fun solutionColor(s: GnssState, stale: Boolean): Color = when {
    s.connection != ConnectionStatus.CONNECTED || stale -> SingleRed
    else -> solutionColor(s.solution)
}

fun solutionColor(sol: SolutionType): Color = when (sol) {
    SolutionType.FIX -> FixGreen
    SolutionType.FLOAT -> FloatYellow
    SolutionType.DGPS -> DgpsOrange
    SolutionType.NONE -> OfflineGrey
    else -> SingleRed
}

private val Brand = Color(0xFF0E7C86)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Color(0xFF3B5B66),
    tertiary = Color(0xFFB45309),
    background = Color(0xFFF6F7F8),
    surface = Color.White,
    surfaceVariant = Color(0xFFE6EBEC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5CC8D1),
    onPrimary = Color(0xFF00363B),
    secondary = Color(0xFF9DBAC4),
    tertiary = Color(0xFFF59E0B),
    background = Color(0xFF0F1416),
    surface = Color(0xFF161D20),
    surfaceVariant = Color(0xFF263136),
)

@Composable
fun TopoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

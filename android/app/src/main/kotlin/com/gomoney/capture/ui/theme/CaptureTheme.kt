package com.gomoney.capture.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class ExtendedColors(
    val income: Color,
    val expense: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        income = Color(0xFF1B5E20),
        expense = Color(0xFFB71C1C),
        success = Color(0xFF1B5E20),
        warning = Color(0xFFE65100),
        error = Color(0xFFB71C1C),
    )
}

private val LightExtendedColors = ExtendedColors(
    income = Color(0xFF1B5E20),
    expense = Color(0xFFB71C1C),
    success = Color(0xFF1B5E20),
    warning = Color(0xFFE65100),
    error = Color(0xFFB71C1C),
)

private val DarkExtendedColors = ExtendedColors(
    income = Color(0xFF81C784),
    expense = Color(0xFFE57373),
    success = Color(0xFF81C784),
    warning = Color(0xFFFFB74D),
    error = Color(0xFFE57373),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF0277BD),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC7E7FF),
    onSecondaryContainer = Color(0xFF001E2E),
    surface = Color(0xFFF8F9FA),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1A1C1E),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD0E4FF),
    secondary = Color(0xFF86D1FF),
    onSecondary = Color(0xFF00354E),
    secondaryContainer = Color(0xFF004C6E),
    onSecondaryContainer = Color(0xFFC7E7FF),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE2E2E6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

object CaptureThemeTokens {
    val extendedColors: ExtendedColors
        @Composable
        get() = LocalExtendedColors.current
}

@Composable
fun CaptureTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}

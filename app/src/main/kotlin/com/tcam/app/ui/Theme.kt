package com.tcam.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF2AABEE),
    onPrimary = Color(0xFF00121D),
    primaryContainer = Color(0xFF0B4A6B),
    onPrimaryContainer = Color(0xFFCDEBFF),
    secondary = Color(0xFF8FB8D0),
    background = Color(0xFF0F1419),
    onBackground = Color(0xFFE3E8ED),
    surface = Color(0xFF151C23),
    onSurface = Color(0xFFE3E8ED),
    surfaceVariant = Color(0xFF1E2731),
    onSurfaceVariant = Color(0xFFB4C0CB),
    surfaceContainer = Color(0xFF18212A),
    surfaceContainerHigh = Color(0xFF1E2832),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2B0000),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF3A4652),
)

val SuccessColor = Color(0xFF4CC38A)
val WarningColor = Color(0xFFF2B84B)

@Composable
fun TcamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}

package com.facecollage.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary           = Color(0xFFC77DFF),
    onPrimary         = Color(0xFF1A0D2E),
    primaryContainer  = Color(0xFF3D0066),
    secondary         = Color(0xFF9B5DE5),
    background        = Color(0xFF0D0D1A),
    surface           = Color(0xFF1A1A2E),
    onBackground      = Color(0xFFE0E0FF),
    onSurface         = Color(0xFFE0E0FF),
    outline           = Color(0xFF5C5C8A),
)

@Composable
fun FaceCollageTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content     = content
    )
}
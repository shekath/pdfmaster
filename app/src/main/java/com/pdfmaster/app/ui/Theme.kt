package com.pdfmaster.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF2F5BEA), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5FF), onPrimaryContainer = Color(0xFF0A2378),
    secondary = Color(0xFF5B6475), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E9F0), onSecondaryContainer = Color(0xFF1B2130),
    background = Color(0xFFF6F7FA), onBackground = Color(0xFF151821),
    surface = Color.White, onSurface = Color(0xFF151821),
    surfaceVariant = Color(0xFFEEF1F6), onSurfaceVariant = Color(0xFF5B6475),
    surfaceContainer = Color(0xFFF1F3F7), surfaceContainerHigh = Color(0xFFEBEEF3), surfaceContainerLow = Color(0xFFFAFBFC),
    outline = Color(0xFFCBD1DC), outlineVariant = Color(0xFFE3E7EE),
    error = Color(0xFFC62839), onError = Color.White, errorContainer = Color(0xFFFFE1E4), onErrorContainer = Color(0xFF5C0612),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB3C4FF), onPrimary = Color(0xFF00267D),
    primaryContainer = Color(0xFF1F3EA8), onPrimaryContainer = Color(0xFFDDE5FF),
    secondary = Color(0xFFBEC6D9), onSecondary = Color(0xFF283041),
    secondaryContainer = Color(0xFF2B3140), onSecondaryContainer = Color(0xFFDDE2EE),
    background = Color(0xFF101217), onBackground = Color(0xFFE3E5EC),
    surface = Color(0xFF171A20), onSurface = Color(0xFFE3E5EC),
    surfaceVariant = Color(0xFF222630), onSurfaceVariant = Color(0xFFA9B0C0),
    surfaceContainer = Color(0xFF1C1F26), surfaceContainerHigh = Color(0xFF242832), surfaceContainerLow = Color(0xFF14161B),
    outline = Color(0xFF424856), outlineVariant = Color(0xFF2C313C),
    error = Color(0xFFFFB3BA), onError = Color(0xFF680014), errorContainer = Color(0xFF8C1022), onErrorContainer = Color(0xFFFFDADD),
)

private val Type = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** [mode] is "system", "light" or "dark". */
@Composable
fun isDark(mode: String): Boolean = when (mode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }

@Composable
fun PdfMasterTheme(mode: String = "system", content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isDark(mode)) Dark else Light, typography = Type, content = content)
}

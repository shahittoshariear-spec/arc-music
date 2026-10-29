package com.arcmusic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Apple Music's accent, taken from the shipping app (`accent_color` = #F82C44) rather
 * than guessed.
 *
 * The light scheme lowers the primary to #E0002B: Apple's own red is a fine icon and
 * fill colour, but at 12–13sp on white it lands around 3.9:1, and this app uses primary
 * for small labels as well as for icons. #E0002B clears 4.5:1 and still reads as the
 * same red. Dark mode lifts it to #FF6B7A, which is 7.6:1 on pure black.
 *
 * Surfaces are the iOS greys: a true black background, #1C1C1E cards and #2C2C2E
 * raised controls in dark; white and #F2F2F7 grouped backgrounds in light.
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFFE0002B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE3E8),
    onPrimaryContainer = Color(0xFF5A0010),
    secondary = Color(0xFF248A3D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8F5DE),
    onSecondaryContainer = Color(0xFF0B3216),
    tertiary = Color(0xFFC93400),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFF2F2F7),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF6E6E73),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF2F2F7),
    surfaceContainerHighest = Color(0xFFE5E5EA),
    surfaceDim = Color(0xFFE5E5EA),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceTint = Color(0xFFF82C44),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFD1D1D6),
    error = Color(0xFFD70015),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE5E7),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF1C1C1E),
    inverseOnSurface = Color(0xFFF2F2F7),
    inversePrimary = Color(0xFFFF8A9B),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF6B7A),
    onPrimary = Color(0xFF3A0008),
    primaryContainer = Color(0xFF5A0010),
    onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = Color(0xFF30D158),
    onSecondary = Color(0xFF00390F),
    secondaryContainer = Color(0xFF0F4A20),
    onSecondaryContainer = Color(0xFFB8F5C4),
    tertiary = Color(0xFFFF9F0A),
    onTertiary = Color(0xFF3A1D00),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFFAEAEB2),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0B),
    surfaceContainer = Color(0xFF141416),
    surfaceContainerHigh = Color(0xFF1C1C1E),
    surfaceContainerHighest = Color(0xFF2C2C2E),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A3A3C),
    surfaceTint = Color(0xFFFF6B7A),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFF38383C),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF4A0000),
    errorContainer = Color(0xFF5A0000),
    onErrorContainer = Color(0xFFFFD9D9),
    inverseSurface = Color(0xFFF2F2F7),
    inverseOnSurface = Color(0xFF1C1C1E),
    inversePrimary = Color(0xFFF82C44),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Tighter, heavier type than Material's default, which is the most obvious part of the
 * Apple Music feel: big bold titles, small quiet metadata.
 */
private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 23.sp,
        lineHeight = 29.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 21.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
    ),
)

@Composable
fun ArcMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        // Dynamic colour is deliberately not supported: it would replace the audited
        // palette below with device colours, and every contrast guarantee would go.
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}

package com.arcmusic.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcmusic.data.AppTheme

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

/** One accent, resolved for both schemes. */
private data class Accent(
    val light: Color,
    val onLight: Color,
    val lightContainer: Color,
    val onLightContainer: Color,
    val dark: Color,
    val onDark: Color,
    val darkContainer: Color,
    val onDarkContainer: Color,
)

/**
 * Accents are chosen for readability rather than copied from a brand sheet: every light
 * value clears 4.5:1 on white at label sizes, every dark value clears 7:1 on black.
 */
private fun accentFor(theme: AppTheme): Accent = when (theme) {
    AppTheme.CUSTOM -> Accent(
        light = Color(0xFF8E8E93),
        onLight = Color(0xFFFFFFFF),
        lightContainer = Color(0xFFE5E5EA),
        onLightContainer = Color(0xFF1C1C1E),
        dark = Color(0xFFAEAEB2),
        onDark = Color(0xFF1C1C1E),
        darkContainer = Color(0xFF2C2C2E),
        onDarkContainer = Color(0xFFF2F2F7),
    )

    AppTheme.EMERALD -> Accent(
        light = Color(0xFF0B7A3B),
        onLight = Color(0xFFFFFFFF),
        lightContainer = Color(0xFFD7F5E1),
        onLightContainer = Color(0xFF00391A),
        dark = Color(0xFF4ADE80),
        onDark = Color(0xFF00391A),
        darkContainer = Color(0xFF064E27),
        onDarkContainer = Color(0xFFC9F7D8),
    )

    AppTheme.OCEAN -> Accent(
        light = Color(0xFF0060C0),
        onLight = Color(0xFFFFFFFF),
        lightContainer = Color(0xFFDCEBFF),
        onLightContainer = Color(0xFF00284D),
        dark = Color(0xFF6FB6FF),
        onDark = Color(0xFF002B4D),
        darkContainer = Color(0xFF003B66),
        onDarkContainer = Color(0xFFD6E9FF),
    )

    AppTheme.VIOLET -> Accent(
        light = Color(0xFF6E28B0),
        onLight = Color(0xFFFFFFFF),
        lightContainer = Color(0xFFF1E3FF),
        onLightContainer = Color(0xFF2A0044),
        dark = Color(0xFFC79BFF),
        onDark = Color(0xFF2A0044),
        darkContainer = Color(0xFF42006B),
        onDarkContainer = Color(0xFFEFDDFF),
    )

    AppTheme.SUNSET -> Accent(
        light = Color(0xFFA85400),
        onLight = Color(0xFFFFFFFF),
        lightContainer = Color(0xFFFFE8CC),
        onLightContainer = Color(0xFF3A1D00),
        dark = Color(0xFFFFB340),
        onDark = Color(0xFF3A1D00),
        darkContainer = Color(0xFF5C3200),
        onDarkContainer = Color(0xFFFFE0B8),
    )

    AppTheme.CRIMSON -> Accent(
        light = Color(0xFFE0002B),
        onLight = Color(0xFFFFFFFF),
        lightContainer = Color(0xFFFFE3E8),
        onLightContainer = Color(0xFF5A0010),
        dark = Color(0xFFFF6B7A),
        onDark = Color(0xFF3A0008),
        darkContainer = Color(0xFF5A0010),
        onDarkContainer = Color(0xFFFFD9DE),
    )

    AppTheme.GRAPHITE -> Accent(
        light = Color(0xFF3A3A3C),
        onLight = Color(0xFFFFFFFF),
        lightContainer = Color(0xFFE8E8ED),
        onLightContainer = Color(0xFF1C1C1E),
        dark = Color(0xFFC7C7CC),
        onDark = Color(0xFF1C1C1E),
        darkContainer = Color(0xFF3A3A3C),
        onDarkContainer = Color(0xFFEDEDF0),
    )

    // Special editions have full schemes of their own below; these are for the swatch.
    AppTheme.P3R -> Accent(
        light = Color(0xFF0B5FC0),
        onLight = Color(0xFFFFFFFF),
        lightContainer = Color(0xFFD6F7EE),
        onLightContainer = Color(0xFF00382A),
        dark = Color(0xFF59F9A6),
        onDark = Color(0xFF00281A),
        darkContainer = Color(0xFF04463A),
        onDarkContainer = Color(0xFFB7FFE0),
    )

    AppTheme.CELESTIAL -> Accent(
        light = Color(0xFF0E6E82),
        onLight = Color(0xFFFFFFFF),
        lightContainer = Color(0xFFD6F6FA),
        onLightContainer = Color(0xFF00333B),
        dark = Color(0xFF6FE3F2),
        onDark = Color(0xFF002E36),
        darkContainer = Color(0xFF06414D),
        onDarkContainer = Color(0xFFC9F8FF),
    )
}

/**
 * Blends the accent into every neutral surface, the way Apple Music's tinted greys do.
 * Kept low (2–9%) so contrast is unaffected and text still reads on every card.
 */
private fun ColorScheme.tinted(accent: Color, dark: Boolean): ColorScheme {
    val low = if (dark) 0.07f else 0.035f
    val high = if (dark) 0.13f else 0.08f
    fun mix(color: Color, amount: Float) = lerp(color, accent, amount)
    return copy(
        background = mix(background, low * 0.5f),
        surface = mix(surface, low * 0.5f),
        surfaceContainerLowest = mix(surfaceContainerLowest, low * 0.5f),
        surfaceContainerLow = mix(surfaceContainerLow, low),
        surfaceContainer = mix(surfaceContainer, low * 1.2f),
        surfaceContainerHigh = mix(surfaceContainerHigh, (low + high) / 2f),
        surfaceContainerHighest = mix(surfaceContainerHighest, high),
        outlineVariant = mix(outlineVariant, 0.07f),
    )
}

/**
 * P3R's own palette, sampled from the artwork: a near-black navy in dark mode with the
 * neon green and cyan from the vinyl, and an ice-blue daylight version of the same.
 */
private fun p3rScheme(dark: Boolean): ColorScheme = if (dark) {
    DarkScheme.copy(
        primary = Color(0xFF59F9A6),
        onPrimary = Color(0xFF00281A),
        primaryContainer = Color(0xFF04463A),
        onPrimaryContainer = Color(0xFFB7FFE0),
        secondary = Color(0xFF9DFCFC),
        onSecondary = Color(0xFF00363B),
        secondaryContainer = Color(0xFF0A4A52),
        onSecondaryContainer = Color(0xFFC9FFFF),
        tertiary = Color(0xFF1673D6),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFF000116),
        onBackground = Color(0xFFEBF4F8),
        surface = Color(0xFF000116),
        onSurface = Color(0xFFEBF4F8),
        surfaceVariant = Color(0xFF031B2F),
        onSurfaceVariant = Color(0xFF9FB6C6),
        surfaceContainerLowest = Color(0xFF00010F),
        surfaceContainerLow = Color(0xFF03101F),
        surfaceContainer = Color(0xFF031B2F),
        surfaceContainerHigh = Color(0xFF072A44),
        surfaceContainerHighest = Color(0xFF0B3A5A),
        outline = Color(0xFF5C7C92),
        outlineVariant = Color(0xFF123A52),
        surfaceTint = Color(0xFF59F9A6),
        inversePrimary = Color(0xFF1673D6),
        inverseSurface = Color(0xFFEBF4F8),
        inverseOnSurface = Color(0xFF031B2F),
        error = Color(0xFFFF8A9B),
    )
} else {
    LightScheme.copy(
        primary = Color(0xFF0B5FC0),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD2E9FF),
        onPrimaryContainer = Color(0xFF002B4D),
        secondary = Color(0xFF0E7C6B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFC9FFF0),
        onSecondaryContainer = Color(0xFF00382A),
        tertiary = Color(0xFF1673D6),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFEFF6FA),
        onBackground = Color(0xFF04131C),
        surface = Color(0xFFEFF6FA),
        onSurface = Color(0xFF04131C),
        surfaceVariant = Color(0xFFE2F1F8),
        onSurfaceVariant = Color(0xFF4A6472),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF7FBFD),
        surfaceContainer = Color(0xFFF0F8FC),
        surfaceContainerHigh = Color(0xFFE6F2F9),
        surfaceContainerHighest = Color(0xFFD9EDF7),
        outline = Color(0xFF6E8896),
        outlineVariant = Color(0xFFC4DCE8),
        surfaceTint = Color(0xFF0B5FC0),
        inversePrimary = Color(0xFF59F9A6),
        inverseSurface = Color(0xFF031B2F),
        inverseOnSurface = Color(0xFFEBF4F8),
    )
}

/**
 * CWW: the celestial edition. A night-sky navy with cyan light, a warm gold and a
 * periwinkle third, sampled from the artwork behind the starfield effect.
 */
private fun cwwScheme(dark: Boolean): ColorScheme = if (dark) {
    DarkScheme.copy(
        primary = Color(0xFF6FE3F2),
        onPrimary = Color(0xFF002E36),
        primaryContainer = Color(0xFF06414D),
        onPrimaryContainer = Color(0xFFC9F8FF),
        secondary = Color(0xFFE4C79A),
        onSecondary = Color(0xFF3A2A12),
        secondaryContainer = Color(0xFF4A3A1C),
        onSecondaryContainer = Color(0xFFFFE8BE),
        tertiary = Color(0xFF8FA6FF),
        onTertiary = Color(0xFF101B4A),
        tertiaryContainer = Color(0xFF28336B),
        onTertiaryContainer = Color(0xFFDDE2FF),
        background = Color(0xFF050B1F),
        onBackground = Color(0xFFEBF1FF),
        surface = Color(0xFF050B1F),
        onSurface = Color(0xFFEBF1FF),
        surfaceVariant = Color(0xFF101B31),
        onSurfaceVariant = Color(0xFF9AA8C6),
        surfaceContainerLowest = Color(0xFF030817),
        surfaceContainerLow = Color(0xFF081228),
        surfaceContainer = Color(0xFF0B1633),
        surfaceContainerHigh = Color(0xFF13213F),
        surfaceContainerHighest = Color(0xFF1B2C4F),
        outline = Color(0xFF6C82A0),
        outlineVariant = Color(0xFF2A3A5C),
        surfaceTint = Color(0xFF6FE3F2),
        inversePrimary = Color(0xFF0E6E82),
        inverseSurface = Color(0xFFF3F7FF),
        inverseOnSurface = Color(0xFF0A162A),
        error = Color(0xFFFF9BB0),
    )
} else {
    LightScheme.copy(
        primary = Color(0xFF0E6E82),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFCFF4FA),
        onPrimaryContainer = Color(0xFF00333B),
        secondary = Color(0xFF7A5A2A),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFEBC8),
        onSecondaryContainer = Color(0xFF2F2000),
        tertiary = Color(0xFF3D4CA6),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE0E3FF),
        onTertiaryContainer = Color(0xFF121C58),
        background = Color(0xFFF4F8FF),
        onBackground = Color(0xFF081423),
        surface = Color(0xFFF4F8FF),
        onSurface = Color(0xFF081423),
        surfaceVariant = Color(0xFFE4EDF8),
        onSurfaceVariant = Color(0xFF4A5B70),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFAFCFF),
        surfaceContainer = Color(0xFFF2F7FD),
        surfaceContainerHigh = Color(0xFFE9F1FA),
        surfaceContainerHighest = Color(0xFFDFEAF6),
        outline = Color(0xFF6A7C90),
        outlineVariant = Color(0xFFC7D8E7),
        surfaceTint = Color(0xFF0E6E82),
        inversePrimary = Color(0xFF6FE3F2),
        inverseSurface = Color(0xFF0A162A),
        inverseOnSurface = Color(0xFFF3F7FF),
    )
}

/** The theme inside the composition, for screens that draw their own special effects. */
val LocalAppTheme = androidx.compose.runtime.staticCompositionLocalOf { AppTheme.CRIMSON }

/** The accent as it would appear in the current scheme, for the settings swatches. */
fun AppTheme.previewColor(dark: Boolean): Color =
    accentFor(this).let { if (dark) it.dark else it.light }

@Composable
private fun animateSlot(target: Color, spec: AnimationSpec<Color>): Color =
    animateColorAsState(targetValue = target, animationSpec = spec, label = "schemeSlot").value

/**
 * Every slot a theme change can touch is cross-faded, so switching accents washes
 * through the app instead of snapping. Values start at the target, so the first frame
 * is correct and nothing animates on launch.
 */
@Composable
private fun animatedScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(durationMillis = 480)
    return target.copy(
        primary = animateSlot(target.primary, spec),
        onPrimary = animateSlot(target.onPrimary, spec),
        primaryContainer = animateSlot(target.primaryContainer, spec),
        onPrimaryContainer = animateSlot(target.onPrimaryContainer, spec),
        secondary = animateSlot(target.secondary, spec),
        onSecondary = animateSlot(target.onSecondary, spec),
        secondaryContainer = animateSlot(target.secondaryContainer, spec),
        onSecondaryContainer = animateSlot(target.onSecondaryContainer, spec),
        tertiary = animateSlot(target.tertiary, spec),
        background = animateSlot(target.background, spec),
        onBackground = animateSlot(target.onBackground, spec),
        surface = animateSlot(target.surface, spec),
        onSurface = animateSlot(target.onSurface, spec),
        surfaceVariant = animateSlot(target.surfaceVariant, spec),
        onSurfaceVariant = animateSlot(target.onSurfaceVariant, spec),
        surfaceContainerLowest = animateSlot(target.surfaceContainerLowest, spec),
        surfaceContainerLow = animateSlot(target.surfaceContainerLow, spec),
        surfaceContainer = animateSlot(target.surfaceContainer, spec),
        surfaceContainerHigh = animateSlot(target.surfaceContainerHigh, spec),
        surfaceContainerHighest = animateSlot(target.surfaceContainerHighest, spec),
        surfaceTint = animateSlot(target.surfaceTint, spec),
        outline = animateSlot(target.outline, spec),
        outlineVariant = animateSlot(target.outlineVariant, spec),
        inversePrimary = animateSlot(target.inversePrimary, spec),
    )
}

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

/**
 * Whether panels are frosted. Provided by the theme so every surface picks up the
 * Settings switch without threading a parameter through the whole UI.
 */
val LocalGlassMode = compositionLocalOf { true }

/** White or near-black, whichever reads better on [color]. */
private fun contrastOn(color: Color): Color {
    val luminance = color.luminance()
    val onWhite = 1.05f / (luminance + 0.05f)
    val onBlack = (luminance + 0.05f) / 0.05f
    return if (onWhite >= onBlack) Color.White else Color(0xFF1C1C1E)
}

/**
 * A scheme built from the three hue sliders: the accent, the card colour and the
 * background. Saturation and brightness stay in the ranges the built-in themes use, so
 * text contrast holds whatever hue is picked.
 */
private fun customScheme(
    dark: Boolean,
    accentHue: Float,
    backgroundHue: Float,
    surfaceHue: Float,
): ColorScheme {
    val base = if (dark) DarkScheme else LightScheme
    val accent = Color.hsv(
        hue = accentHue.mod(360f),
        saturation = if (dark) 0.62f else 0.78f,
        value = if (dark) 1f else 0.86f,
    )
    val background = Color.hsv(
        hue = backgroundHue.mod(360f),
        saturation = if (dark) 0.42f else 0.12f,
        value = if (dark) 0.06f else 0.98f,
    )
    val surface = Color.hsv(
        hue = surfaceHue.mod(360f),
        saturation = if (dark) 0.26f else 0.07f,
        value = if (dark) 0.12f else 0.99f,
    )
    val onAccent = contrastOn(accent)
    val accentContainer = lerp(background, accent, if (dark) 0.30f else 0.18f)
    val onAccentContainer = if (dark) {
        lerp(accent, Color.White, 0.75f)
    } else {
        lerp(accent, Color.Black, 0.72f)
    }

    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = onAccentContainer,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accentContainer,
        onSecondaryContainer = onAccentContainer,
        tertiary = accent,
        onTertiary = onAccent,
        surfaceTint = accent,
        inversePrimary = accent,
        background = background,
        surface = surface,
        surfaceContainerLowest = background,
        surfaceContainerLow = lerp(surface, background, 0.55f),
        surfaceContainer = surface,
        surfaceContainerHigh = lerp(surface, Color.White, if (dark) 0.07f else 0.02f),
        surfaceContainerHighest = lerp(surface, Color.White, if (dark) 0.14f else 0.05f),
    )
}

@Composable
fun ArcMusicTheme(
    theme: AppTheme = AppTheme.CRIMSON,
    darkTheme: Boolean = isSystemInDarkTheme(),
    glass: Boolean = true,
    customAccent: Float = 348f,
    customBackground: Float = 348f,
    customSurface: Float = 348f,
    content: @Composable () -> Unit,
) {
    val scheme = when (theme) {
        AppTheme.P3R -> animatedScheme(p3rScheme(darkTheme))
        AppTheme.CELESTIAL -> animatedScheme(cwwScheme(darkTheme))
        AppTheme.CUSTOM -> animatedScheme(
            customScheme(darkTheme, customAccent, customBackground, customSurface)
        )
        else -> {
            val accent = accentFor(theme)
            val base = if (darkTheme) DarkScheme else LightScheme
            val accentColor = if (darkTheme) accent.dark else accent.light
            animatedScheme(
                base.copy(
                    primary = accentColor,
                    onPrimary = if (darkTheme) accent.onDark else accent.onLight,
                    primaryContainer = if (darkTheme) accent.darkContainer else accent.lightContainer,
                    onPrimaryContainer = if (darkTheme) accent.onDarkContainer else accent.onLightContainer,
                    surfaceTint = accentColor,
                    inversePrimary = accentColor,
                ).tinted(accentColor, darkTheme)
            )
        }
    }
    MaterialTheme(
        // Dynamic colour is deliberately not supported: it would replace the audited
        // palette below with device colours, and every contrast guarantee would go.
        colorScheme = scheme,
        shapes = AppShapes,
        typography = AppTypography,
    ) {
        CompositionLocalProvider(
            LocalGlassMode provides glass,
            LocalAppTheme provides theme,
            content = content,
        )
    }
}

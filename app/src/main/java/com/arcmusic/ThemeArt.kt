package com.arcmusic

import com.arcmusic.data.AppTheme

/**
 * Picks the built-in artwork that should stand in for a free accent colour.
 *
 * The custom theme has no icon set of its own, and it used to borrow Crimson's — which
 * is why a customised app still showed a rose badge on the lock screen, in the shade and
 * on the OEM "dynamic island". Choosing the nearest built-in hue keeps those surfaces
 * in the colour the user actually picked.
 */
fun iconThemeForAccent(hue: Float): AppTheme {
    val value = ((hue % 360f) + 360f) % 360f
    return when {
        value < 15f || value >= 340f -> AppTheme.CRIMSON
        value < 50f -> AppTheme.SUNSET
        value < 130f -> AppTheme.EMERALD
        value < 230f -> AppTheme.OCEAN
        value < 310f -> AppTheme.VIOLET
        else -> AppTheme.CRIMSON
    }
}

/** The theme whose icon set represents [theme]: custom themes borrow their nearest hue. */
fun iconThemeFor(theme: AppTheme, customAccentHue: Float): AppTheme =
    if (theme == AppTheme.CUSTOM) iconThemeForAccent(customAccentHue) else theme

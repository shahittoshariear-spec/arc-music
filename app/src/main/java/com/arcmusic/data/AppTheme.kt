package com.arcmusic.data

import androidx.annotation.StringRes
import com.arcmusic.R

/**
 * The accent palette the whole app is tinted with.
 *
 * Six flat themes in the spirit of the icon family, two special editions with their own
 * artwork and effects (P3R and Celestial), and one the user builds from hue sliders.
 */
enum class AppTheme(@param:StringRes val label: Int) {
    EMERALD(R.string.theme_emerald),
    OCEAN(R.string.theme_ocean),
    VIOLET(R.string.theme_violet),
    SUNSET(R.string.theme_sunset),
    CRIMSON(R.string.theme_crimson),
    GRAPHITE(R.string.theme_graphite),

    /** Special editions: their own palettes, icons and background effects. */
    P3R(R.string.theme_p3r),
    CELESTIAL(R.string.theme_cww),

    /** Accent, surface and background picked by the user. */
    CUSTOM(R.string.theme_custom),
    ;

    val isSpecial: Boolean
        get() = this == P3R || this == CELESTIAL
}

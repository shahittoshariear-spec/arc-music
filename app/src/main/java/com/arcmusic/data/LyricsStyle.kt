package com.arcmusic.data

import androidx.annotation.StringRes
import com.arcmusic.R

/** How the lyrics screen draws a song. */
enum class LyricsStyle(
    @param:StringRes val label: Int,
    @param:StringRes val blurb: Int,
) {
    /** The whole song, scrolling, with the current line lit. */
    CLASSIC(R.string.lyrics_style_classic, R.string.lyrics_style_classic_help),

    /** One line centred and bright, the rest faded to forty percent. */
    SPOTLIGHT(R.string.lyrics_style_spotlight, R.string.lyrics_style_spotlight_help),

    /** Only the current line, filling in word by word as it is sung. */
    RIVER(R.string.lyrics_style_river, R.string.lyrics_style_river_help),

    /** Only the current line sharp, the neighbouring lines blurred behind it. */
    FLOATING(R.string.lyrics_style_floating, R.string.lyrics_style_floating_help),
}

package com.arcmusic.data

import android.content.Context

/**
 * Lyrics the user typed or that were fetched for them. Raw text is kept as-is so it can
 * be either LRC (timed) or plain lines; the parser decides at display time.
 */
class LyricsRepository private constructor(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(key: String): String? =
        prefs.getString(key, null)?.takeIf { it.isNotBlank() }

    fun put(key: String, raw: String) {
        if (raw.isBlank()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, raw).apply()
        }
    }

    companion object {
        private const val PREFS = "arc_music_lyrics"

        @Volatile
        private var instance: LyricsRepository? = null

        fun get(context: Context): LyricsRepository =
            instance ?: synchronized(this) {
                instance ?: LyricsRepository(context.applicationContext).also { instance = it }
            }
    }
}

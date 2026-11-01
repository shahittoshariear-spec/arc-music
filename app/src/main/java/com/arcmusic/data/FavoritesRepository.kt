package com.arcmusic.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the user has hearted, stored as the same stable key the player uses: a media
 * id for saved files, the page URL for streams. A StringSet is enough because the
 * display data already lives in the library or in the search result being shown.
 */
class FavoritesRepository private constructor(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _keys = MutableStateFlow(
        prefs.getStringSet(KEY, emptySet())?.toSet().orEmpty()
    )
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    fun toggle(key: String): Boolean {
        val current = _keys.value
        val next = if (key in current) current - key else current + key
        prefs.edit().putStringSet(KEY, next).apply()
        _keys.value = next
        return key in next
    }

    companion object {
        private const val PREFS = "arc_music_favorites"
        private const val KEY = "keys"

        @Volatile
        private var instance: FavoritesRepository? = null

        fun get(context: Context): FavoritesRepository =
            instance ?: synchronized(this) {
                instance ?: FavoritesRepository(context.applicationContext).also { instance = it }
            }
    }
}

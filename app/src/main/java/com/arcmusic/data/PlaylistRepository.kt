package com.arcmusic.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A playlist the user built from songs in their own library. Tracks are stored as
 * history ids, so a deleted song simply falls out of the list instead of leaving a
 * dangling row.
 */
data class LocalPlaylist(
    val id: String,
    val name: String,
    val trackIds: List<String>,
    val createdAt: Long,
)

class PlaylistRepository private constructor(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _playlists = MutableStateFlow(load())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    fun create(name: String): LocalPlaylist {
        val playlist = LocalPlaylist(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "New playlist" },
            trackIds = emptyList(),
            createdAt = System.currentTimeMillis(),
        )
        save(_playlists.value + playlist)
        return playlist
    }

    fun rename(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        save(_playlists.value.map { if (it.id == id) it.copy(name = clean) else it })
    }

    fun delete(id: String) {
        save(_playlists.value.filterNot { it.id == id })
    }

    fun addTrack(id: String, trackId: String) {
        save(
            _playlists.value.map { playlist ->
                if (playlist.id == id && trackId !in playlist.trackIds) {
                    playlist.copy(trackIds = playlist.trackIds + trackId)
                } else {
                    playlist
                }
            }
        )
    }

    fun removeTrack(id: String, trackId: String) {
        save(
            _playlists.value.map { playlist ->
                if (playlist.id == id) {
                    playlist.copy(trackIds = playlist.trackIds - trackId)
                } else {
                    playlist
                }
            }
        )
    }

    private fun save(next: List<LocalPlaylist>) {
        val array = JSONArray()
        next.forEach { playlist ->
            array.put(
                JSONObject().apply {
                    put("id", playlist.id)
                    put("name", playlist.name)
                    put("createdAt", playlist.createdAt)
                    put("tracks", JSONArray(playlist.trackIds))
                }
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
        _playlists.value = next
    }

    private fun load(): List<LocalPlaylist> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val id = json.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val tracks = json.optJSONArray("tracks") ?: JSONArray()
                    add(
                        LocalPlaylist(
                            id = id,
                            name = json.optString("name", "Playlist"),
                            trackIds = buildList {
                                for (track in 0 until tracks.length()) {
                                    tracks.optString(track)
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let(::add)
                                }
                            },
                            createdAt = json.optLong("createdAt"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val PREFS = "arc_music_playlists"
        private const val KEY = "playlists"

        @Volatile
        private var instance: PlaylistRepository? = null

        fun get(context: Context): PlaylistRepository =
            instance ?: synchronized(this) {
                instance ?: PlaylistRepository(context.applicationContext).also { instance = it }
            }
    }
}

package com.arcmusic.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A single-file snapshot of everything the app knows: the library records, playlists,
 * favourites, statistics and lyrics, plus the settings.
 *
 * Rather than teaching each repository how to serialise itself a second time, this
 * copies the preference stores they already write. That keeps the format in step with
 * the app automatically — a field added later is backed up without touching this file —
 * and restoring is a plain write followed by a restart, which is exactly how the
 * repositories re-read their state.
 */
object Backup {

    private const val FORMAT = 1

    /** Preference files owned by the repositories. */
    private val STORES = listOf(
        "yt_converter_history",
        "yt_converter_settings",
        "arc_music_favorites",
        "arc_music_playlists",
        "arc_music_lyrics",
        "arc_music_stats",
    )

    data class Summary(val songs: Int, val playlists: Int, val favorites: Int, val statsPlays: Int)

    fun summarize(context: Context): Summary {
        val app = context.applicationContext
        return Summary(
            songs = HistoryRepository.get(app).items.value.size,
            playlists = PlaylistRepository.get(app).playlists.value.size,
            favorites = FavoritesRepository.get(app).keys.value.size,
            statsPlays = StatsRepository.get(app).state.value.total,
        )
    }

    fun export(context: Context): String {
        val app = context.applicationContext
        val summary = summarize(app)
        val stores = JSONObject()
        STORES.forEach { name ->
            val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            val entries = JSONObject()
            prefs.all.forEach { (key, value) -> entries.put(key, encode(value)) }
            stores.put(name, entries)
        }

        return JSONObject().apply {
            put("app", "A(rc)")
            put("format", FORMAT)
            put("exportedAt", System.currentTimeMillis())
            put("songs", summary.songs)
            put("playlists", summary.playlists)
            put("favorites", summary.favorites)
            put("statsPlays", summary.statsPlays)
            put("stores", stores)
        }.toString(2)
    }

    /** Returns true when the file looked like one of ours and was written back. */
    fun restore(context: Context, raw: String): Boolean {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        if (json.optString("app") != "A(rc)") return false
        val stores = json.optJSONObject("stores") ?: return false

        var restored = false
        STORES.forEach { name ->
            val entries = stores.optJSONObject(name) ?: return@forEach
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            entries.keys().forEach { key ->
                val value = entries.optJSONObject(key) ?: return@forEach
                when (value.optString("type")) {
                    "string" -> editor.putString(key, value.optString("value"))
                    "int" -> editor.putInt(key, value.optInt("value"))
                    "long" -> editor.putLong(key, value.optLong("value"))
                    "float" -> editor.putFloat(key, value.optDouble("value").toFloat())
                    "boolean" -> editor.putBoolean(key, value.optBoolean("value"))
                    "set" -> {
                        val array = value.optJSONArray("value") ?: JSONArray()
                        editor.putStringSet(
                            key,
                            buildSet { for (i in 0 until array.length()) add(array.optString(i)) },
                        )
                    }
                }
            }
            // Committed, not applied: the process is about to be restarted by the user
            // and every repository re-reads these files on construction.
            restored = editor.commit() || restored
        }
        return restored
    }

    private fun encode(value: Any?): JSONObject = when (value) {
        is String -> JSONObject().put("type", "string").put("value", value)
        is Int -> JSONObject().put("type", "int").put("value", value)
        is Long -> JSONObject().put("type", "long").put("value", value)
        is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
        is Boolean -> JSONObject().put("type", "boolean").put("value", value)
        is Set<*> -> JSONObject().put(
            "type",
            "set",
        ).put("value", JSONArray(value.mapNotNull { it as? String }))
        else -> JSONObject().put("type", "string").put("value", value?.toString().orEmpty())
    }
}

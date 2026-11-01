package com.arcmusic.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Looks lyrics up on [LRCLIB](https://lrclib.net), which is free, needs no key and
 * returns both timed (LRC) and plain text. That is the same idea as Automatag: fill in
 * the metadata from the song rather than asking the user to type it.
 *
 * An exact match is tried first, then a search. A miss is normal, so this returns null
 * instead of throwing.
 */
object LyricsClient {

    private const val GET = "https://lrclib.net/api/get"
    private const val SEARCH = "https://lrclib.net/api/search"
    private const val USER_AGENT =
        "ArcMusic/1.0 (https://github.com/shahittoshariear-spec/yt-converter)"

    suspend fun fetch(
        artist: String?,
        title: String,
        durationSeconds: Long,
    ): String? = withContext(Dispatchers.IO) {
        if (artist.isNullOrBlank() || title.isBlank()) return@withContext null

        val cleanArtist = clean(artist)
        val cleanTitle = clean(title)
        if (cleanArtist.isBlank() || cleanTitle.isBlank()) return@withContext null

        // Duration is deliberately not sent: LRCLIB rejects the whole lookup when it is
        // a second out, and the track title plus artist is usually enough.
        val exact = buildString {
            append(GET)
            append("?artist_name=").append(URLEncoder.encode(cleanArtist, "UTF-8"))
            append("&track_name=").append(URLEncoder.encode(cleanTitle, "UTF-8"))
        }
        pick(getJson(exact))
            ?: search("$cleanArtist $cleanTitle")
            ?: search(cleanTitle)
    }

    private fun search(query: String): String? {
        val url = buildString {
            append(SEARCH)
            append("?q=").append(URLEncoder.encode(query, "UTF-8"))
        }
        val array = getJsonArray(url) ?: return null
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            pick(entry)?.let { return it }
        }
        return null
    }

    private fun pick(json: JSONObject?): String? {
        if (json == null) return null
        json.optString("syncedLyrics").takeIf { it.isNotBlank() }?.let { return it }
        return json.optString("plainLyrics").takeIf { it.isNotBlank() }
    }

    private fun getJson(url: String): JSONObject? =
        runCatching { JSONObject(request(url)) }.getOrNull()

    private fun getJsonArray(url: String): JSONArray? =
        runCatching { JSONArray(request(url)) }.getOrNull()

    private fun request(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) return ""
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * YouTube titles arrive with noise ("(Official Video)", "[HD]", topic suffixes) that
     * lyrics databases do not use.
     */
    private fun clean(raw: String): String {
        var value = raw
        value = value.replace(Regex("""\s*[\[(].*?[\])]"""), "")
        value = value.replace(Regex("""(?i)\s*-\s*topic\s*$"""), "")
        value = value.replace(Regex("""(?i)\s*\(official\s*(video|audio|music video)\)"""), "")
        value = value.replace(Regex("""\s+"""), " ")
        return value.trim()
    }
}

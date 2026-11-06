package com.arcmusic.downloader

import android.content.Context
import com.arcmusic.data.AudioFormat
import com.arcmusic.data.DownloadRecord
import com.arcmusic.data.HistoryRepository
import com.arcmusic.data.LyricsRepository
import com.arcmusic.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** One song from the signed-in Suno account. */
data class SunoClip(
    val id: String,
    val title: String,
    val artist: String,
    val audioUrl: String,
    val coverUrl: String?,
    /** The lyrics Suno stored when the song was generated. */
    val lyrics: String?,
    val tags: String?,
    val durationSeconds: Long,
)

/**
 * A very small Suno client.
 *
 * Suno has no public API key flow, so the app reuses the browser session the user
 * creates in the in-app WebView: the cookies are harvested after sign-in and sent to
 * the studio endpoints. The lyrics are part of the clip metadata, which is exactly what
 * makes a Suno download self-contained — no catalogue lookup will ever find them.
 */
object SunoClient {

    private const val PREFS = "suno_session"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_ARTIST = "artist"

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"

    private val ENDPOINTS = listOf(
        "https://studio-api.prod.suno.com/api/feed/v2?page=%d",
        "https://studio-api.suno.ai/api/feed/?page=%d",
        "https://studio-api.prod.suno.com/api/feed/?page=%d",
    )

    /** Pages fetched per endpoint; Suno returns about twenty clips per page. */
    private const val MAX_PAGES = 6

    /** Last HTTP status seen, so the UI can say "sign in again" instead of "empty". */
    @Volatile
    var lastStatus: Int = 0
        private set

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveSession(context: Context, cookie: String) {
        val trimmed = cookie.trim()
        if (trimmed.isEmpty()) return
        prefs(context).edit().putString(KEY_COOKIE, trimmed).apply()
    }

    fun cookie(context: Context): String? =
        prefs(context).getString(KEY_COOKIE, null)?.takeIf { it.isNotBlank() }

    fun artist(context: Context): String? =
        prefs(context).getString(KEY_ARTIST, null)?.takeIf { it.isNotBlank() }

    fun isSignedIn(context: Context): Boolean = cookie(context) != null

    fun signOut(context: Context) {
        prefs(context).edit().remove(KEY_COOKIE).remove(KEY_ARTIST).apply()
    }

    /** Anything that looks like a Clerk/Suno session cookie. */
    fun looksSignedIn(rawCookie: String?): Boolean {
        val value = rawCookie.orEmpty().lowercase(Locale.ROOT)
        return value.contains("__client=") ||
            value.contains("__session=") ||
            value.contains("suno_session=") ||
            value.contains("suno_auth=")
    }

    suspend fun clips(context: Context): Result<List<SunoClip>> = withContext(Dispatchers.IO) {
        val session = cookie(context) ?: return@withContext Result.failure(
            SunoException("Sign in to Suno first"),
        )
        lastStatus = 0
        for (template in ENDPOINTS) {
            val found = LinkedHashMap<String, SunoClip>()
            for (page in 0 until MAX_PAGES) {
                val body = runCatching {
                    request(String.format(Locale.ROOT, template, page), session)
                }.getOrNull() ?: break
                val parsed = runCatching { parse(body) }.getOrDefault(emptyList())
                if (parsed.isEmpty()) break
                parsed.forEach { found.putIfAbsent(it.id, it) }
                // A short page is the end of the library.
                if (parsed.size < 15) break
            }
            if (found.isNotEmpty()) {
                val clips = found.values.toList()
                val name = clips.firstOrNull()?.artist?.takeIf { it.isNotBlank() }
                if (name != null) saveArtist(context, name)
                return@withContext Result.success(clips)
            }
        }
        val reason = when (lastStatus) {
            401, 403 -> "Suno sign-in expired. Sign in again."
            404 -> "Suno's library endpoint moved. Try again later."
            0 -> "Could not reach Suno."
            else -> "Suno returned no songs (HTTP $lastStatus)."
        }
        Result.failure(SunoException(reason))
    }

    private fun saveArtist(context: Context, artist: String) {
        prefs(context).edit().putString(KEY_ARTIST, artist).apply()
    }

    private fun parse(body: String): List<SunoClip> {
        val trimmed = body.trim()
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONObject(trimmed).optJSONArray("clips") ?: JSONArray()
        }
        val clips = mutableListOf<SunoClip>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val audio = item.optString("audio_url")
            if (audio.isBlank()) continue
            val metadata = item.optJSONObject("metadata") ?: JSONObject()
            val instrumental = metadata.optBoolean("is_instrumental", false)
            val lyrics = if (instrumental) {
                null
            } else {
                listOf("prompt", "lyrics")
                    .map { metadata.optString(it) }
                    .firstOrNull { it.isNotBlank() }
            }
            clips += SunoClip(
                id = item.optString("id").ifBlank { audio },
                title = item.optString("title").ifBlank { "Suno song" },
                artist = item.optString("display_name")
                    .ifBlank { item.optString("handle") }
                    .ifBlank { "Suno" },
                audioUrl = audio,
                coverUrl = item.optString("image_large_url")
                    .takeIf { it.isNotBlank() }
                    ?: item.optString("image_url").takeIf { it.isNotBlank() },
                lyrics = lyrics,
                tags = metadata.optString("tags").takeIf { it.isNotBlank() },
                durationSeconds = metadata.optDouble("duration", 0.0).toLong(),
            )
        }
        return clips
    }

    private fun request(url: String, cookie: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", "https://suno.com/")
            setRequestProperty("Origin", "https://suno.com")
            bearer(cookie)?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        return try {
            val code = connection.responseCode
            lastStatus = code
            if (code !in 200..299) {
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (_: Throwable) {
            lastStatus = 0
            null
        } finally {
            connection.disconnect()
        }
    }

    /** Suno's web client also sends the Clerk token as a bearer. */
    private fun bearer(cookie: String): String? = cookie
        .split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("__client=") || it.startsWith("__session=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotBlank() }
}

class SunoException(message: String) : Exception(message)

/**
 * Downloads one clip and files it in the library with its cover and lyrics attached.
 *
 * The audio never passes through yt-dlp: Suno hands out a direct file URL, so a plain
 * HTTP copy is both faster and lighter. Lyrics are written into the file's tag block and
 * also into the app's own lyrics store, because no online lyrics service knows them.
 */
object SunoDownloader {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"

    suspend fun download(
        context: Context,
        clip: SunoClip,
        onProgress: (Float) -> Unit,
    ): DownloadRecord? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val cookie = SunoClient.cookie(app) ?: return@withContext null
        val work = File(app.cacheDir, "suno").apply { mkdirs() }
        // The CDN usually serves MP3, but a lossless clip keeps its own extension so the
        // library label and the player do not lie about it.
        val extension = clip.audioUrl.substringBefore('?')
            .substringAfterLast('.')
            .lowercase(Locale.ROOT)
            .takeIf { it in setOf("mp3", "flac", "wav", "m4a", "ogg") }
            ?: "mp3"
        val audio = File(work, "${safeName(clip.title)}.$extension")
        val cover = File(work, "cover_${clip.id.hashCode()}.img")

        try {
            onProgress(0.02f)
            fetch(clip.audioUrl, cookie, audio) { value ->
                onProgress(0.02f + value * 0.76f)
            }
            if (audio.length() <= 0L) return@withContext null

            clip.coverUrl?.let { url ->
                runCatching { fetch(url, cookie, cover) {} }
            }
            onProgress(0.84f)

            val published = MediaPublisher.publish(
                app,
                audio,
                SettingsRepository.get(app).folder.value,
            ) ?: return@withContext null

            val storedCover = cover
                .takeIf { it.isFile && it.length() > 0L }
                ?.let { AutoTag.persistCover(app, it, published.uri) }

            HistoryRepository.get(app).add(
                DownloadRecord(
                    id = published.uri,
                    title = clip.title,
                    uploader = clip.artist,
                    thumbnailUrl = storedCover,
                    formatName = when (extension) {
                        "mp3" -> AudioFormat.MP3.name
                        "flac" -> AudioFormat.FLAC.name
                        else -> AudioFormat.ORIGINAL.name
                    },
                    uri = published.uri,
                    fileName = published.fileName,
                    mimeType = published.mimeType,
                    sizeBytes = published.sizeBytes,
                    durationSeconds = clip.durationSeconds,
                    createdAt = System.currentTimeMillis(),
                    album = "Suno",
                    albumArtist = clip.artist,
                    genre = clip.tags?.take(80),
                    sourceUrl = "https://suno.com/song/${clip.id}",
                )
            )

            // Tagging is best-effort: a CDN file with an odd container must not lose the
            // download that already succeeded.
            runCatching {
                val current = HistoryRepository.get(app).items.value
                    .firstOrNull { it.id == published.uri }
                if (current != null) {
                    Tagger.apply(
                        context = app,
                        record = current,
                        edits = TagEdits(
                            title = clip.title,
                            artist = clip.artist,
                            album = "Suno",
                            albumArtist = clip.artist,
                            genre = clip.tags.orEmpty(),
                            year = "",
                            track = "",
                            lyrics = clip.lyrics.orEmpty(),
                        ),
                        cover = cover.takeIf { it.isFile && it.length() > 0L },
                    )
                }
            }
            onProgress(0.94f)

            val record = HistoryRepository.get(app).items.value
                .firstOrNull { it.id == published.uri } ?: return@withContext null
            if (!storedCover.isNullOrBlank() && record.thumbnailUrl != storedCover) {
                runCatching {
                    HistoryRepository.get(app).update(record.copy(thumbnailUrl = storedCover))
                }
            }
            if (!clip.lyrics.isNullOrBlank()) {
                runCatching { LyricsRepository.get(app).put(record.id, clip.lyrics) }
            }
            onProgress(1f)
            return@withContext record
        } finally {
            audio.delete()
            cover.delete()
        }
    }

    private fun fetch(
        url: String,
        cookie: String,
        target: File,
        onProgress: (Float) -> Unit,
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Cookie", cookie)
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", "https://suno.com/")
        }
        try {
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        if (total > 0L) {
                            onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun safeName(raw: String): String = raw
        .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()
        .ifBlank { "Suno song" }
        .take(70)
}

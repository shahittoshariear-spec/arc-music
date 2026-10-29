package com.arcmusic.downloader

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.arcmusic.data.AudioFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Metadata for one video, resolved either by probing or by expanding a playlist. */
data class MediaInfo(
    val url: String,
    val title: String,
    val uploader: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
)

/** Why a conversion failed, in terms the UI can act on. */
enum class FailureKind { STALE_EXTRACTOR, SIGN_IN_REQUIRED, AGE_RESTRICTED, NETWORK, UNAVAILABLE, UNKNOWN }

/**
 * Thin wrapper over the bundled yt-dlp/FFmpeg.
 *
 * [ensureReady] unpacks the Python runtime and FFmpeg on first use, which takes a
 * couple of seconds, so it is always called off the main thread.
 */
object YtDlpEngine {

    @Volatile
    private var ready = false

    fun ensureReady(appContext: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            YoutubeDL.getInstance().init(appContext)
            FFmpeg.getInstance().init(appContext)
            ready = true
        }
    }

    suspend fun ensureReadyAsync(context: Context) = withContext(Dispatchers.IO) {
        ensureReady(context.applicationContext)
    }

    /** Reads title/thumbnail/duration for a single video without downloading it. */
    suspend fun probeSingle(context: Context, url: String): MediaInfo? = withContext(Dispatchers.IO) {
        ensureReady(context.applicationContext)
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--skip-download")
            addOption("--no-warnings")
            // Probing needs the session too, otherwise a private video resolves to
            // nothing and its card is queued with no title.
            CookieStore.pathOrNull(context)?.let { addOption("--cookies", it) }
        }
        val response = YoutubeDL.execute(request, null, false, null)
        val root = extractJson(response.out.orEmpty()) ?: return@withContext null
        parseInfo(root)
    }

    /**
     * Runs a YouTube search and returns one row per result.
     *
     * `--flat-playlist` keeps this to a single extraction pass: the entries carry the
     * id, title, channel and duration but no per-video metadata, which is exactly what
     * a result row needs and far cheaper than probing twenty videos one by one. The
     * per-line `--dump-json` form is deliberate: it is what lets the bundled library
     * tolerate a dead entry in the middle of the result list.
     */
    suspend fun search(context: Context, query: String, limit: Int = 20): List<MediaInfo> =
        withContext(Dispatchers.IO) {
            ensureReady(context.applicationContext)
            val request = YoutubeDLRequest("ytsearch$limit:$query").apply {
                addOption("--flat-playlist")
                addOption("--dump-json")
                addOption("--skip-download")
                addOption("--no-warnings")
                CookieStore.pathOrNull(context)?.let { addOption("--cookies", it) }
            }
            YoutubeDL.execute(request, null, false, null).out.orEmpty()
                .lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("{") }
                .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                .mapNotNull(::parseInfo)
                .take(limit)
                .toList()
        }

    /**
     * Lists the entries of any playlist or tab URL without probing each video.
     *
     * This is what backs the signed-in library: `playlist?list=LL` (liked),
     * `playlist?list=WL` (watch later) and `feed/playlists` (the user's own playlists),
     * none of which resolve without the cookie file. Entries are flat, so a 200-song
     * list costs one extraction pass instead of 200.
     */
    suspend fun tabEntries(context: Context, url: String, limit: Int = 300): List<MediaInfo> =
        withContext(Dispatchers.IO) {
            ensureReady(context.applicationContext)
            val request = YoutubeDLRequest(url).apply {
                addOption("--flat-playlist")
                addOption("--dump-json")
                addOption("--skip-download")
                addOption("--no-warnings")
                CookieStore.pathOrNull(context)?.let { addOption("--cookies", it) }
            }
            YoutubeDL.execute(request, null, false, null).out.orEmpty()
                .lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("{") }
                .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                .mapNotNull(::parseInfo)
                .take(limit)
                .toList()
        }

    /**
     * Resolves a direct audio URL for streaming, without downloading anything.
     *
     * m4a is preferred because Android decodes it natively everywhere; the other
     * fallbacks only appear when a video has no m4a audio track.
     */
    suspend fun streamUrl(context: Context, url: String): String? =
        withContext(Dispatchers.IO) {
            ensureReady(context.applicationContext)
            val request = YoutubeDLRequest(url).apply {
                addOption("-f", "bestaudio[ext=m4a]/bestaudio[ext=mp4]/bestaudio/best")
                addOption("-g")
                addOption("--no-playlist")
                addOption("--no-warnings")
                CookieStore.pathOrNull(context)?.let { addOption("--cookies", it) }
            }
            YoutubeDL.execute(request, null, false, null).out.orEmpty()
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("http") }
        }

    /**
     * Pulls the newest yt-dlp release from GitHub and returns the version now in use,
     * or null when the update could not be applied.
     *
     * `ALREADY_UP_TO_DATE` counts as success: either way the engine afterwards is the
     * newest one GitHub offers, which is the only thing the caller cares about.
     */
    suspend fun updateEngine(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            ensureReady(context.applicationContext)
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            if (status == YoutubeDL.UpdateStatus.DONE ||
                status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE
            ) {
                engineVersion(context) ?: "unknown"
            } else {
                null
            }
        }.getOrNull()
    }

    /** The yt-dlp version the app will actually run, or null when it cannot be read. */
    fun engineVersion(context: Context): String? = runCatching {
        ensureReady(context.applicationContext)
        YoutubeDL.getInstance().version(context.applicationContext)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Adds the audio-extraction options shared by every queued job. */
    fun applyAudioOptions(
        request: YoutubeDLRequest,
        format: AudioFormat,
        trimNonMusic: Boolean,
        playlist: Boolean = false,
        cookiesPath: String? = null,
    ) {
        // A signed-in session unlocks private, liked and age-restricted videos.
        cookiesPath?.let { request.addOption("--cookies", it) }

        // A playlist is deliberately left to yt-dlp as a single run: one Python start
        // and one extraction pass instead of one per song, and yt-dlp handles the
        // pagination and the per-entry retries itself. `--ignore-errors` matters there
        // so one dead video does not abandon the other 375.
        if (playlist) {
            request.addOption("--ignore-errors")
        } else {
            request.addOption("--no-playlist")
        }
        request.addOption("--newline")
        request.addOption("--no-mtime")
        request.addOption("-x")
        format.ytDlpAudioFormat?.let { request.addOption("--audio-format", it) }
        format.audioQuality?.let { request.addOption("--audio-quality", it) }
        request.addOption("--embed-metadata")
        if (format != AudioFormat.ORIGINAL) {
            // Cover art only makes sense in the formats that support it, and
            // ORIGINAL is meant to come through untouched.
            request.addOption("--embed-thumbnail")
            request.addOption("--convert-thumbnails", "jpg")
        }
        if (trimNonMusic) {
            request.addOption(
                "--sponsorblock-remove",
                "sponsor,selfpromo,interaction,intro,outro",
            )
        }
    }

    /**
     * Maps yt-dlp's error output onto something the UI can explain and act on.
     *
     * Order matters: the bot-check message also contains "sign in", but it is usually
     * fixed by updating yt-dlp, whereas an age gate is not.
     */
    fun classify(message: String?): FailureKind {
        val text = message.orEmpty().lowercase()
        fun has(vararg needles: String) = needles.any { text.contains(it) }

        return when {
            has("not a bot", "nsig", "unable to extract", "failed to extract",
                "no video formats", "http error 403", "signature",
                "not available on this app", "po token", "po_token",
                "use --cookies") -> FailureKind.STALE_EXTRACTOR

            has("age-restricted", "age restricted", "confirm your age", "members-only",
                "members only", "login required", "sign in to confirm") ->
                FailureKind.AGE_RESTRICTED

            has("unable to connect", "connection", "timed out", "timeout", "network",
                "temporary failure in name resolution", "ssl", "certificate") ->
                FailureKind.NETWORK

            has("video unavailable", "private video", "removed by the uploader",
                "does not exist", "not available in your country", "no longer available") ->
                FailureKind.UNAVAILABLE

            else -> FailureKind.UNKNOWN
        }
    }

    /**
     * True for the gates that only the user's own session can clear.
     *
     * YouTube has tightened these through 2026, and the newest yt-dlp still answers
     * "Sign in to confirm you're not a bot" without cookies. Telling someone to press
     * Update engine again is therefore a dead end, and this is what lets the UI stop.
     */
    fun isSignInGate(message: String?): Boolean {
        val text = message.orEmpty().lowercase()
        return text.contains("not a bot") ||
            text.contains("sign in to confirm") ||
            text.contains("not available on this app") ||
            text.contains("po token") ||
            text.contains("po_token") ||
            text.contains("use --cookies") ||
            text.contains("age-restricted") ||
            text.contains("age restricted") ||
            text.contains("confirm your age") ||
            text.contains("members-only") ||
            text.contains("members only") ||
            text.contains("login required")
    }

    private fun parseInfo(json: JSONObject): MediaInfo? {
        val id = json.optString("id").takeIf { it.isNotBlank() }
        val title = json.optString("title").takeIf { it.isNotBlank() } ?: id ?: return null
        val pageUrl = json.optString("webpage_url")

        // The YouTube-specific fallbacks below would invent a wrong link for anyone
        // else's content, so gate them on the extractor actually being YouTube.
        val extractor = json.optString("extractor_key")
            .ifBlank { json.optString("ie_key") }
            .ifBlank { json.optString("extractor") }
        val isYouTube = extractor.contains("youtube", ignoreCase = true) ||
            YOUTUBE_HOST.containsMatchIn(pageUrl)

        val url = sequenceOf("webpage_url", "url", "original_url")
            .map { json.optString(it) }
            .firstOrNull { it.startsWith("http") }
            ?: id?.takeIf { isYouTube }?.let { "https://www.youtube.com/watch?v=$it" }
            ?: return null

        return MediaInfo(
            url = url,
            title = title,
            uploader = json.optString("uploader").takeIf { it.isNotBlank() }
                ?: json.optString("channel").takeIf { it.isNotBlank() },
            thumbnailUrl = explicitThumbnail(json)
                ?: if (isYouTube) youtubeThumbnail(id) else null,
            durationSeconds = json.optDouble("duration", 0.0).toLong(),
        )
    }

    /**
     * `thumbnail` is a plain URL but `thumbnails` is an array, and passing the array
     * to `optString` would just return its serialised form.
     */
    private fun explicitThumbnail(json: JSONObject): String? {
        json.optString("thumbnail").takeIf { it.startsWith("http") }?.let { return it }
        val array = json.optJSONArray("thumbnails") ?: return null
        for (index in array.length() - 1 downTo 0) {
            val url = array.optJSONObject(index)?.optString("url").orEmpty()
            if (url.startsWith("http")) return url
        }
        return null
    }

    private fun youtubeThumbnail(id: String?): String? {
        if (id == null || id.length != 11) return null
        if (!id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    }
    /** yt-dlp can be chatty, so slice out the outermost JSON object. */
    private fun extractJson(output: String): JSONObject? {
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(output.substring(start, end + 1)) }.getOrNull()
    }

    /** Matches only a real YouTube host, not a URL that merely mentions it. */
    private val YOUTUBE_HOST = Regex(
        """^https?://([a-z0-9-]+\.)*(youtube\.com|youtu\.be|youtube-nocookie\.com)/""",
        RegexOption.IGNORE_CASE,
    )
}

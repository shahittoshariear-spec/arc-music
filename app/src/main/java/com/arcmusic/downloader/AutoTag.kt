package com.arcmusic.downloader

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.arcmusic.data.DownloadRecord
import com.arcmusic.data.HistoryRepository
import com.arcmusic.data.LyricsRepository
import com.arcmusic.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Auto(rc): the metadata pass that runs after a download finishes.
 *
 * Two catalogue providers are tried because availability differs by country: MusicBrainz
 * (free, no key, worldwide) first, then the iTunes Search API, which is blocked in some
 * regions and simply returns nothing there. A match is checked against the YouTube title
 * and channel before anything is written, because a wrong cover is worse than none.
 *
 * Tags are written into the file through [Tagger]; lyrics come from LRCLIB and are kept
 * in-app next to hand-typed ones. The queue is persisted, so a long playlist resumes
 * after a restart, and a provider that throttles parks the queue instead of hammering it.
 */
object AutoTag {

    /** What the settings screen shows, so Auto(rc) is never a silent black box. */
    data class Status(
        val queued: Int = 0,
        val tagged: Int = 0,
        val skipped: Int = 0,
        val running: Boolean = false,
        /** Why the last song was skipped, in words, for the settings screen. */
        val lastIssue: String? = null,
    )

    private enum class Outcome { DONE, MISS, RATE_LIMITED }

    private const val PREFS = "arc_music_autotag"
    private const val KEY_PENDING = "pending"
    private const val MAX_PENDING = 600
    private const val ITUNES = "https://itunes.apple.com/search"
    private const val MUSICBRAINZ = "https://musicbrainz.org/ws/2/recording/"
    private const val COVER_ART = "https://coverartarchive.org/release/"
    private const val AUDIODB = "https://www.theaudiodb.com/api/v1/json/2/searchtrack.php"
    private const val REQUEST_DELAY = 1_100L
    private const val BACKGROUND_REQUEST_DELAY = 6_000L
    private const val RATE_LIMIT_PAUSE = 3 * 60 * 1000L
    private const val USER_AGENT =
        "ArcMusic/1.1 (https://github.com/shahittoshariear-spec/arc-music)"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private data class Pending(val id: String, val at: Long)

    private val pending = ArrayDeque<Pending>()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    private var context: Context? = null
    private var prefs: SharedPreferences? = null
    private var running = false
    private var blockedUntil = 0L

    @Volatile
    private var lastNetworkFailed = false

    /** False while no UI is visible: the queue advances, but far more gently. */
    @Volatile
    private var uiVisible = true

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
    }

    /** One artwork suggestion for the editor. `source` is "album" or "video". */
    data class CoverChoice(val url: String, val source: String)

    /** Everything the editor needs to fill its fields and offer artwork. */
    data class Proposal(
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val genre: String,
        val year: String,
        val track: String,
        val covers: List<CoverChoice>,
        val matched: Boolean,
    )

    /**
     * Looks a song up for the tag editor. The editor passes the *current* fields, so the
     * user can fix a bad title and press Auto(rc) again — the same workflow Automatag
     * users rely on.
     */
    /**
     * Every confident catalogue match, best first. Singles, album versions, live takes
     * and collaborations all show up, so the user can pick the right one instead of
     * living with the first guess.
     */
    suspend fun proposals(
        title: String,
        artist: String?,
        thumbnailUrl: String?,
    ): List<Proposal> = withContext(Dispatchers.IO) {
        val youtubeTitle = title.trim()
        val uploader = channelName(artist)
        val (queryTitle, titleArtist) = splitArtistTitle(youtubeTitle)
        val searchArtist = titleArtist ?: uploader
        val hint = listOfNotNull(uploader, titleArtist).joinToString(" ").ifBlank { null }
        val preferHq = context?.let { SettingsRepository.get(it).hqArtwork.value } == true
        val videoCover = if (preferHq) Thumbnails.highRes(thumbnailUrl) else thumbnailUrl

        val found = mutableListOf<Lookup>()
        fun collect(block: () -> List<Lookup>) {
            if (found.size >= 4) return
            val next = runCatching(block).getOrDefault(emptyList())
                .filter { confident(youtubeTitle, it.trackName, hint, it.artistName) }
                // Live takes rarely carry usable cover art, and matching one to a studio
                // upload was producing wrong albums and photos. There is no setting: the
                // user downloads official audio and lyric videos, not concert rips.
                .filterNot { isLive(it.trackName, it.collectionName) }
            found += next
        }

        collect { searchMusicBrainz(queryTitle, searchArtist) }
        collect { searchItunes(queryTitle, searchArtist) }
        collect { searchAudioDb(queryTitle, searchArtist) }

        found
            .distinctBy {
                "${it.trackName.lowercase()}|${it.artistName.lowercase()}|" +
                    it.collectionName.lowercase()
            }
            .take(6)
            .map { lookup ->
                val covers = buildList {
                    lookup.artUrl?.let { add(CoverChoice(it, "album")) }
                    videoCover?.takeIf { it.isNotBlank() }
                        ?.let { add(CoverChoice(it, "video")) }
                }.distinctBy { it.url }

                Proposal(
                    title = lookup.trackName,
                    artist = lookup.artistName.ifBlank { artist.orEmpty() },
                    album = lookup.collectionName,
                    albumArtist = lookup.artistName.ifBlank { artist.orEmpty() },
                    genre = lookup.genre,
                    year = lookup.releaseDate.take(4),
                    track = lookup.trackNumber,
                    covers = covers,
                    matched = true,
                )
            }
    }

    suspend fun propose(
        title: String,
        artist: String?,
        thumbnailUrl: String?,
    ): Proposal = proposals(title, artist, thumbnailUrl).firstOrNull()
        ?: Proposal(
            title = title.trim(),
            artist = artist.orEmpty(),
            album = "",
            albumArtist = artist.orEmpty(),
            genre = "",
            year = "",
            track = "",
            covers = listOfNotNull(
                thumbnailUrl?.takeIf { it.isNotBlank() }?.let { CoverChoice(it, "video") }
            ),
            matched = false,
        )

    /** Downloads one artwork URL into the cache for the editor to hand to [Tagger]. */
    suspend fun cacheCover(appContext: Context, url: String): File? =
        withContext(Dispatchers.IO) { downloadCover(appContext.applicationContext, url) }

    /**
     * Keeps the artwork the library should display after a tag write. A local file is
     * used instead of the provider URL so the cover keeps working offline, and so the
     * row visibly changes the moment Auto(rc) finishes.
     */
    fun persistCover(appContext: Context, file: File, id: String): String? = runCatching {
        val directory = File(appContext.applicationContext.filesDir, "covers")
        directory.mkdirs()
        // One file per song. A timestamped name left a new copy behind on every re-tag,
        // and nothing ever deleted them, so the folder grew without bound.
        val target = File(directory, "cover_${id.hashCode()}.jpg")
        file.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        if (target.length() > 0L) "file://" + target.absolutePath else null
    }.getOrNull()

    @Volatile
    private var lastReason: String = ""

    /** Called once from the application so leftover work survives a restart. */
    fun init(appContext: Context) {
        val app = appContext.applicationContext
        synchronized(lock) {
            if (context == null) {
                context = app
                prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                restore()
                _status.update { it.copy(queued = pending.size) }
            }
        }
        kick()
    }

    /** Queues finished records. A no-op when the toggle is off, unless forced. */
    fun enqueue(appContext: Context, ids: List<String>, force: Boolean = false) {
        if (ids.isEmpty()) return
        if (!force && !SettingsRepository.get(appContext).autoTag.value) return
        init(appContext)

        val history = HistoryRepository.get(appContext).items.value
        val queued = System.currentTimeMillis()
        synchronized(lock) {
            ids.forEach { id ->
                if (id.isBlank() || pending.size >= MAX_PENDING) return@forEach
                if (pending.any { it.id == id }) return@forEach
                // A song is auto-tagged once. If Auto(rc) or the editor already wrote
                // its metadata, a background pass must not pick another version later.
                // Explicit runs (force) still go through.
                if (!force) {
                    val record = history.firstOrNull { it.id == id }
                    if (record?.metadataUpdatedAt != null) return@forEach
                }
                pending.addLast(Pending(id, queued))
            }
            persist()
            _status.update {
                it.copy(queued = pending.size, tagged = 0, skipped = 0)
            }
        }
        kick()
    }

    private fun kick() {
        val app = context ?: return
        // The toggle has to gate the worker as well as the queue. With Auto(rc) off,
        // drain() exits on its first check and calls kick() again from its tail, which
        // used to start a new coroutine with no delay — a spin loop that pinned a core
        // for as long as a queued song remained.
        if (!SettingsRepository.get(app).autoTag.value) return
        synchronized(lock) {
            if (running || pending.isEmpty()) return
            if (System.currentTimeMillis() < blockedUntil) return
            running = true
        }
        _status.update { it.copy(running = true) }
        scope.launch { drain() }
    }

    private suspend fun drain() {
        val app = context ?: run {
            synchronized(lock) { running = false }
            _status.update { it.copy(running = false) }
            return
        }

        while (true) {
            // Turning the toggle off keeps the queue for later instead of burning it.
            if (!SettingsRepository.get(app).autoTag.value) break

            val task = synchronized(lock) { pending.removeFirstOrNull() } ?: break
            synchronized(lock) {
                persist()
                _status.update { it.copy(queued = pending.size) }
            }

            val record = HistoryRepository.get(app).items.value.firstOrNull { it.id == task.id }
            if (record != null) {
                // If the metadata was written after this job was queued — the user
                // saved an edit, or Auto(rc) already ran — leave the song alone.
                val writtenAt = record.metadataUpdatedAt
                if (writtenAt != null && writtenAt > task.at) {
                    delay(if (uiVisible) REQUEST_DELAY else BACKGROUND_REQUEST_DELAY)
                    continue
                }

                val outcome = try {
                    enrich(app, record)
                } catch (limited: RateLimited) {
                    Outcome.RATE_LIMITED
                } catch (t: Throwable) {
                    Outcome.MISS
                }
                if (outcome == Outcome.RATE_LIMITED) {
                    // Put it back and stop; the catalogue throttles by IP and a storm
                    // of requests only extends the block.
                    synchronized(lock) {
                        pending.addFirst(task)
                        blockedUntil = System.currentTimeMillis() + RATE_LIMIT_PAUSE
                        persist()
                        _status.update { it.copy(queued = pending.size) }
                    }
                    // Wake up when the block expires. Without this the rest of the queue
                    // waited for the next download or app launch to move again.
                    scope.launch {
                        delay(RATE_LIMIT_PAUSE + 5_000L)
                        kick()
                    }
                    break
                }

                // Even a miss is a completed attempt: mark it so this song is not
                // retried automatically on every future launch.
                val latest = HistoryRepository.get(app).items.value
                    .firstOrNull { it.id == task.id }
                if (latest != null && latest.metadataUpdatedAt == null) {
                    HistoryRepository.get(app)
                        .update(latest.copy(metadataUpdatedAt = System.currentTimeMillis()))
                }

                _status.update {
                    if (outcome == Outcome.DONE) {
                        it.copy(tagged = it.tagged + 1, lastIssue = null)
                    } else {
                        it.copy(
                            skipped = it.skipped + 1,
                            lastIssue = if (outcome == Outcome.RATE_LIMITED) {
                                it.lastIssue
                            } else {
                                lastReason.ifBlank { it.lastIssue }
                            },
                        )
                    }
                }
            }
            delay(if (uiVisible) REQUEST_DELAY else BACKGROUND_REQUEST_DELAY)
        }

        synchronized(lock) { running = false }
        _status.update { it.copy(running = false) }
        // An enqueue that landed while the last song was finishing gets its turn.
        if (System.currentTimeMillis() >= blockedUntil) kick()
    }

    private suspend fun enrich(app: Context, record: DownloadRecord): Outcome {
        lastNetworkFailed = false
        // Only a trailing audio extension belongs to the file name. Cutting at the last
        // dot turned "Mr. Brightside" into "Mr" and cost those songs their match.
        val extension = record.fileName.substringAfterLast('.', "").lowercase()
        val youtubeTitle = if (extension.length in 2..5 &&
            record.title.endsWith(".$extension", ignoreCase = true)
        ) {
            record.title.dropLast(extension.length + 1).trim()
        } else {
            record.title.trim()
        }
        val uploader = channelName(record.uploader)
        val (queryTitle, titleArtist) = splitArtistTitle(youtubeTitle)
        val searchArtist = titleArtist ?: uploader
        val artistHint = listOfNotNull(uploader, titleArtist).joinToString(" ").ifBlank { null }

        val musicBrainz = searchMusicBrainz(queryTitle, searchArtist)
        var match = musicBrainz.firstOrNull {
            confident(youtubeTitle, it.trackName, artistHint, it.artistName) &&
                !isLive(it.trackName, it.collectionName)
        }
        var itunes = emptyList<Lookup>()
        if (match == null) {
            itunes = searchItunes(queryTitle, searchArtist)
            match = itunes.firstOrNull {
                confident(youtubeTitle, it.trackName, artistHint, it.artistName) &&
                    !isLive(it.trackName, it.collectionName)
            }
        }
        var audioDb = emptyList<Lookup>()
        if (match == null) {
            audioDb = searchAudioDb(queryTitle, searchArtist)
            match = audioDb.firstOrNull {
                confident(youtubeTitle, it.trackName, artistHint, it.artistName) &&
                    !isLive(it.trackName, it.collectionName)
            }
        }

        var wrote = false
        if (match != null) {
            // Covers are tried in order: every catalogue candidate first (HQ where the
            // provider has it), then the video thumbnail as the last resort. A region
            // that blocks one host should not cost the song its artwork.
            val preferHq = SettingsRepository.get(app).hqArtwork.value
            val fallbackCover = record.thumbnailUrl?.let {
                if (preferHq) Thumbnails.highRes(it) else it
            }
            val candidates = (musicBrainz + itunes + audioDb)
                .mapNotNull { it.artUrl } + listOfNotNull(fallbackCover)

            var cover: File? = null
            var coverUrl: String? = null
            for (url in candidates.distinct()) {
                val file = downloadCover(app, url) ?: continue
                cover = file
                coverUrl = url
                break
            }
            val storedCover = cover?.let { persistCover(app, it, record.id) }

            wrote = Tagger.apply(
                context = app,
                record = record,
                edits = TagEdits(
                    title = match.trackName,
                    artist = match.artistName,
                    album = match.collectionName,
                    albumArtist = match.artistName,
                    genre = match.genre,
                    year = match.releaseDate.take(4),
                    track = match.trackNumber,
                ),
                cover = cover,
            )
            cover?.delete()

            // The library reads its art from the record, not from the file, so point it
            // at the same picture; otherwise Auto(rc) looks like a no-op. Only the local
            // copy is stored: a remote URL goes blank the moment the host rate-limits it,
            // and nothing outside this process can read a private file path.
            if (wrote) {
                if (!storedCover.isNullOrBlank()) {
                    val current = HistoryRepository.get(app).items.value
                        .firstOrNull { it.id == record.id }
                    if (current != null) {
                        HistoryRepository.get(app).update(current.copy(thumbnailUrl = storedCover))
                    }
                }
            }
        }

        // Lyrics are useful even when the catalogue had nothing: LRCLIB searches by
        // artist and title, which the YouTube metadata already carries.
        lastReason = when {
            match == null && lastNetworkFailed -> "no connection"
            match == null -> "not found"
            !wrote -> "could not write"
            else -> ""
        }
        val updated = HistoryRepository.get(app).items.value.firstOrNull { it.id == record.id }
            ?: record
        val lyrics = runCatching {
            LyricsClient.fetch(
                artist = updated.uploader,
                title = updated.title,
                durationSeconds = updated.durationSeconds,
            )
        }.getOrNull()
        if (lyrics != null) LyricsRepository.get(app).put(updated.id, lyrics)

        return if (wrote) Outcome.DONE else Outcome.MISS
    }

    // ---- Catalogue ---------------------------------------------------------

    private class Lookup(
        val trackName: String,
        val artistName: String,
        val collectionName: String,
        val genre: String,
        val releaseDate: String,
        val trackNumber: String,
        val artUrl: String?,
    )

    /**
     * MusicBrainz: worldwide, no key, and generous enough for a 376-song playlist as
     * long as requests stay around one per second.
     */
    private fun searchMusicBrainz(title: String, artist: String?): List<Lookup> {
        if (title.isBlank()) return emptyList()
        val query = buildString {
            append("recording:\"").append(escapeQuery(title)).append('"')
            if (!artist.isNullOrBlank()) {
                append(" AND artist:\"").append(escapeQuery(artist)).append('"')
            }
        }
        val url = "$MUSICBRAINZ?query=${encode(query)}&fmt=json&limit=5"
        val body = request(url) ?: return emptyList()
        val recordings = runCatching {
            JSONObject(body).optJSONArray("recordings")
        }.getOrNull() ?: return emptyList()

        return buildList {
            for (index in 0 until minOf(recordings.length(), 5)) {
                val recording = recordings.optJSONObject(index) ?: continue
                val trackName = recording.optString("title").takeIf { it.isNotBlank() } ?: continue
                val artistName = recording.optJSONArray("artist-credit")
                    ?.optJSONObject(0)
                    ?.optString("name")
                    ?.takeIf { it.isNotBlank() }
                    .orEmpty()

                val release = recording.optJSONArray("releases")?.optJSONObject(0)
                val releaseId = release?.optString("id")?.takeIf { it.isNotBlank() }
                val releaseTitle = release?.optString("title").orEmpty()
                val releaseDate = release?.optString("date")
                    ?.takeIf { it.isNotBlank() }
                    ?: recording.optString("first-release-date").orEmpty()
                // The search endpoint names this array `track`, not `tracks`.
                val trackNumber = release?.optJSONArray("media")
                    ?.optJSONObject(0)
                    ?.optJSONArray("track")
                    ?.optJSONObject(0)
                    ?.optString("number")
                    .orEmpty()

                val genre = recording.optJSONArray("genres")
                    ?.optJSONObject(0)
                    ?.optString("name")
                    .orEmpty()

                add(
                    Lookup(
                        trackName = trackName,
                        artistName = artistName,
                        collectionName = releaseTitle,
                        genre = genre,
                        releaseDate = releaseDate,
                        trackNumber = trackNumber,
                        artUrl = releaseId?.let { COVER_ART + it + "/front-1200" },
                    )
                )
            }
        }
    }

    /** iTunes: good artwork size and precise titles, where the endpoint is reachable. */
    private fun searchItunes(title: String, artist: String?): List<Lookup> {
        val term = listOfNotNull(title, artist).joinToString(" ").trim()
        if (term.isBlank()) return emptyList()
        val url = "$ITUNES?term=${encode(term)}&media=music&entity=song&limit=3"
        val body = request(url) ?: return emptyList()
        val results = runCatching {
            JSONObject(body).optJSONArray("results")
        }.getOrNull() ?: return emptyList()

        return buildList {
            for (index in 0 until minOf(results.length(), 3)) {
                val entry = results.optJSONObject(index) ?: continue
                val trackName = entry.optString("trackName").takeIf { it.isNotBlank() } ?: continue
                add(
                    Lookup(
                        trackName = trackName,
                        artistName = entry.optString("artistName")
                            .takeIf { it.isNotBlank() }
                            .orEmpty(),
                        collectionName = entry.optString("collectionName")
                            .takeIf { it.isNotBlank() }
                            .orEmpty(),
                        genre = entry.optString("primaryGenreName")
                            .takeIf { it.isNotBlank() }
                            .orEmpty(),
                        releaseDate = entry.optString("releaseDate").orEmpty(),
                        trackNumber = entry.optInt("trackNumber")
                            .takeIf { it > 0 }
                            ?.toString()
                            .orEmpty(),
                        artUrl = entry.optString("artworkUrl100")
                            .takeIf { it.isNotBlank() }
                            ?.replace("100x100bb", "1200x1200bb"),
                    )
                )
            }
        }
    }

    /**
     * TheAudioDB: a third source, reachable where the other two are not. Its free test
     * key needs no account and it serves square album art.
     */
    private fun searchAudioDb(title: String, artist: String?): List<Lookup> {
        if (title.isBlank() || artist.isNullOrBlank()) return emptyList()
        val url = "$AUDIODB?s=${encode(artist)}&t=${encode(title)}"
        val body = request(url) ?: return emptyList()
        val tracks = runCatching {
            JSONObject(body).optJSONArray("track")
        }.getOrNull() ?: return emptyList()

        return buildList {
            for (index in 0 until minOf(tracks.length(), 3)) {
                val entry = tracks.optJSONObject(index) ?: continue
                val trackName = entry.optString("strTrack").takeIf { it.isNotBlank() } ?: continue
                val art = entry.optString("strTrackThumb").takeIf { it.isNotBlank() }
                    ?: entry.optString("strAlbumThumb").takeIf { it.isNotBlank() }
                add(
                    Lookup(
                        trackName = trackName,
                        artistName = entry.optString("strArtist"),
                        collectionName = entry.optString("strAlbum"),
                        genre = entry.optString("strGenre"),
                        releaseDate = entry.optString("intYearReleased"),
                        trackNumber = entry.optString("intTrackNumber"),
                        artUrl = art,
                    )
                )
            }
        }
    }

    private fun request(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = connection.responseCode
            if (code == 403 || code == 429 || code == 503) throw RateLimited()
            if (code !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (limited: RateLimited) {
            throw limited
        } catch (t: Throwable) {
            lastNetworkFailed = true
            null
        } finally {
            connection.disconnect()
        }
    }

    private class RateLimited : RuntimeException()

    private fun downloadCover(app: Context, url: String?): File? {
        if (url.isNullOrBlank()) return null
        val target = File(app.cacheDir, "autotag_cover_${System.currentTimeMillis()}.jpg")

        // The library stores artwork as a file:// URI after a run, so a second pass can
        // be handed its own cover back; URL() does not handle that on Android.
        if (url.startsWith("file://") || url.startsWith("content://")) {
            val copied = runCatching {
                val stream = if (url.startsWith("content://")) {
                    app.contentResolver.openInputStream(Uri.parse(url))
                } else {
                    File(Uri.parse(url).path.orEmpty()).inputStream()
                }
                stream?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                looksLikeImage(target)
            }.getOrDefault(false)
            if (copied) return target
            target.delete()
            return null
        }

        val copied = runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "image/*")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                return@runCatching false
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            // Providers do not agree on Content-Type (Archive.org serves
            // application/octet-stream), so the bytes decide instead.
            looksLikeImage(target)
        }.getOrDefault(false)
        if (copied) return target
        target.delete()
        return null
    }

    /** Cover Art Archive answers 404 with a JSON body; never hand that to FFmpeg. */
    private fun looksLikeImage(file: File): Boolean {
        if (file.length() <= 0L) return false
        val header = ByteArray(8)
        val read = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(0)
        if (read < 4) return false
        val jpeg = header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()
        val png = header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte()
        val webp = read >= 8 &&
            header[0] == 'R'.code.toByte() &&
            header[7] == 'P'.code.toByte()
        return jpeg || png || webp
    }

    // ---- Matching ----------------------------------------------------------

    /** "Song (Official Video) [4K]" becomes "Song", which is what catalogues index. */
    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("""[\(\[\{][^\)\]\}]*[\)\]\}]"""), " ")
        .replace(Regex("""(?i)\b(official|lyrics?|audio|video|visuali[sz]er|hd|hq|4k|mv)\b"""), " ")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()

    /** "Artist - Song (Official Video)" gives a cleaner query than the raw title. */
    private fun splitArtistTitle(raw: String): Pair<String, String?> {
        val cleaned = cleanTitle(raw)
        val dash = cleaned.indexOf(" - ")
        if (dash > 0) {
            val left = cleaned.substring(0, dash).trim()
            val right = cleaned.substring(dash + 3).trim()
            if (left.isNotEmpty() && right.isNotEmpty()) return right to left
        }
        return cleaned to null
    }

    /** YouTube channels append these to the artist name, which breaks a strict match. */
    private fun channelName(raw: String?): String? = raw
        ?.substringBefore(" - Topic")
        ?.removeSuffix("VEVO")
        ?.removeSuffix("Official")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun words(text: String): Set<String> = text
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9 ]"""), " ")
        .split(' ')
        .filter { it.length > 1 }
        .toSet()

    /**
     * A cover from the wrong song is worse than no cover, so the title has to share most
     * of its words. The artist check is looser than an equality test: `ArtistVEVO` and
     * `Artist - Topic` both pass because they contain the artist.
     */
    /** "Live", "(Live at Wembley)", "Live Forever" — the word alone, never "Alive". */
    private val liveMarker = Regex("""(?i)\blive\b""")

    private fun isLive(track: String, album: String): Boolean =
        liveMarker.containsMatchIn(track) || liveMarker.containsMatchIn(album)

    private fun confident(
        youtubeTitle: String,
        foundTitle: String,
        uploader: String?,
        foundArtist: String,
    ): Boolean {
        val mine = words(youtubeTitle)
        val theirs = words(foundTitle)
        if (mine.isEmpty() || theirs.isEmpty()) return true

        val shorter = minOf(mine.size, theirs.size)
        val overlap = mine.intersect(theirs).size.toFloat() / shorter
        if (overlap < 0.55f) return false

        if (uploader.isNullOrBlank() || foundArtist.isBlank()) return true
        val channel = uploader.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]"""), "")
        val artist = foundArtist.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]"""), "")
        if (channel.isEmpty() || artist.isEmpty()) return true
        return channel.contains(artist) || artist.contains(channel)
    }

    private fun escapeQuery(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    // ---- Persistence -------------------------------------------------------

    private fun restore() {
        prefs?.getString(KEY_PENDING, null)
            ?.lineSequence()
            ?.mapNotNull { line ->
                val text = line.trim()
                if (text.isEmpty()) return@mapNotNull null
                // Entries from older builds have no timestamp and cannot be checked
                // against later edits, so they are dropped rather than risking an
                // overwrite of work done since. Explicit runs can re-tag anything.
                val parts = text.split('|')
                if (parts.size != 2) return@mapNotNull null
                Pending(parts[0], parts[1].toLongOrNull() ?: 0L)
            }
            ?.forEach { pending.addLast(it) }
    }

    private fun persist() {
        prefs?.edit()
            ?.putString(KEY_PENDING, pending.joinToString("\n") { "${it.id}|${it.at}" })
            ?.apply()
    }
}

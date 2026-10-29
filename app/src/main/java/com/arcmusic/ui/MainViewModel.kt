package com.arcmusic.ui

import android.app.Application
import android.content.ClipboardManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arcmusic.R
import com.arcmusic.data.AudioFormat
import com.arcmusic.data.DownloadRecord
import com.arcmusic.data.FavoritesRepository
import com.arcmusic.data.HistoryRepository
import com.arcmusic.data.LocalPlaylist
import com.arcmusic.data.LyricsRepository
import com.arcmusic.data.PlaylistRepository
import com.arcmusic.data.SettingsRepository
import com.arcmusic.downloader.CookieStore
import com.arcmusic.downloader.DownloadBus
import com.arcmusic.downloader.DownloadService
import com.arcmusic.downloader.JobKind
import com.arcmusic.downloader.JobPhase
import com.arcmusic.downloader.JobStatus
import com.arcmusic.downloader.LyricsClient
import com.arcmusic.downloader.MediaInfo
import com.arcmusic.downloader.QueueItem
import com.arcmusic.downloader.YtDlpEngine
import com.arcmusic.player.PlayableTrack
import com.arcmusic.player.PlaybackController
import com.arcmusic.player.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class EngineStatus { IDLE, UPDATING, UPDATED, FAILED }

/** One row in the signed-in library: a saved playlist, or one of the fixed feeds. */
data class CollectionInfo(val title: String, val url: String)

/** What the lyrics screen is showing for the current track. */
data class LyricsState(
    val key: String? = null,
    val raw: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val history = HistoryRepository.get(application)
    private val settings = SettingsRepository.get(application)
    private val favoritesRepo = FavoritesRepository.get(application)
    private val playlistRepo = PlaylistRepository.get(application)
    private val lyricsRepo = LyricsRepository.get(application)

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _format = MutableStateFlow(AudioFormat.MP3)
    val format: StateFlow<AudioFormat> = _format.asStateFlow()

    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    private val _engineStatus = MutableStateFlow(EngineStatus.IDLE)
    val engineStatus: StateFlow<EngineStatus> = _engineStatus.asStateFlow()

    /** yt-dlp version in use, once it has been read or updated. */
    private val _engineVersion = MutableStateFlow<String?>(null)
    val engineVersion: StateFlow<String?> = _engineVersion.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** True when the link is a watch URL that also carries a playlist id. */
    val playlistHint: StateFlow<Boolean> = _url
        .map { isCollectionLink(it) && it.contains("v=", ignoreCase = true) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** True when the link is a playlist or channel in its own right. */
    val playlistDetected: StateFlow<Boolean> = _url
        .map { isCollectionLink(it) && !it.contains("v=", ignoreCase = true) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val queue: StateFlow<List<QueueItem>> = DownloadBus.queue
    val items: StateFlow<List<DownloadRecord>> = history.items

    /** True once a YouTube session has been captured from the in-app browser. */
    private val _signedIn = MutableStateFlow(CookieStore.pathOrNull(application) != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()
    val folder: StateFlow<String> = settings.folder
    val trimNonMusic: StateFlow<Boolean> = settings.skipNonMusic

    fun onUrlChange(value: String) {
        _url.value = value
    }

    fun onFormatChange(value: AudioFormat) {
        _format.value = value
    }

    fun clearUrl() {
        _url.value = ""
    }

    /**
     * Pulls every link off the clipboard. One link just fills the field so the format
     * can be chosen; several are queued straight away, which is the whole point of
     * batch mode.
     */
    fun pasteFromClipboard() {
        val text = clipboardText()
        val links = text
            ?.let { raw -> LINKS.findAll(raw).map { cleanLink(it.value) }.filter { it.isNotEmpty() }.distinct().toList() }
            .orEmpty()
        when {
            links.isEmpty() -> emit(text(R.string.no_links))
            links.size == 1 -> _url.value = links.first()
            else -> {
                val chosen = _format.value
                enqueue(links.map { queueItemFor(it, chosen) })
                _url.value = ""
                emit(text(R.string.enqueued_many, links.size))
            }
        }
    }

    fun prefillFromClipboardIfLink() {
        if (_url.value.isNotBlank()) return
        val link = clipboardText()?.let { LINKS.find(it)?.value }?.let(::cleanLink) ?: return
        if (looksLikeVideoLink(link)) _url.value = link
    }

    fun start() {
        val link = normalize(_url.value) ?: run {
            emit(text(R.string.bad_url))
            return
        }
        // A `watch?v=..&list=..` URL defaults to the single video; the playlist is an
        // explicit extra at the user's request.
        if (isCollectionLink(link) && !link.contains("v=", ignoreCase = true)) {
            enqueuePlaylist(link)
        } else {
            enqueueSingle(link)
        }
    }

    fun startWithPlaylist() {
        val link = normalize(_url.value) ?: run {
            emit(text(R.string.bad_url))
            return
        }
        enqueuePlaylist(link)
    }

    /** One video, probed first so its card is populated the moment it is queued. */
    private fun enqueueSingle(link: String) {
        if (_resolving.value) return
        _resolving.value = true
        viewModelScope.launch {
            try {
                val chosen = _format.value
                val info = withContext(Dispatchers.IO) {
                    runCatching { YtDlpEngine.probeSingle(app, link) }.getOrNull()
                }
                enqueue(listOf(queueItem(link, chosen, info)))
                _url.value = ""
                emit(text(R.string.enqueued_one))
            } finally {
                _resolving.value = false
            }
        }
    }

    /**
     * A whole playlist is one engine run rather than hundreds of queue entries: yt-dlp
     * walks the list itself, so it costs one interpreter start instead of one per song,
     * and it handles pagination and per-entry retries internally.
     *
     * There is deliberately no metadata pass up front. The engine reports the playlist
     * name and the song count from its own output, so a 376-song list starts
     * immediately instead of after an extra sweep of the whole playlist.
     */
    private fun enqueuePlaylist(link: String) {
        enqueue(
            listOf(
                QueueItem(
                    id = UUID.randomUUID().toString(),
                    url = link,
                    format = _format.value,
                    kind = JobKind.PLAYLIST,
                )
            )
        )
        _url.value = ""
        emit(text(R.string.enqueued_one))
    }

    fun refreshSession() {
        val present = CookieStore.pathOrNull(app) != null
        _signedIn.value = present
        if (!present) {
            _collections.value = emptyList()
            closeCollection()
        }
    }

    /** A download started from the in-app browser, on the page the user was viewing. */
    fun enqueueFromBrowser(url: String, playlist: Boolean) {
        if (playlist) enqueuePlaylist(url) else enqueueSingle(url)
    }

    fun cancelItem(id: String) {
        val item = queue.value.firstOrNull { it.id == id } ?: return
        if (item.isRunning) {
            // Flag it and kill the process: the flag handles the gaps between yt-dlp
            // invocations, the process kill handles the download itself.
            DownloadBus.requestCancel(id)
            DownloadBus.cancelCurrent()
        } else {
            // Removing is enough — the worker treats "no longer in the queue" as
            // canceled, so an item it already picked up still aborts. The process kill
            // covers the gap where the worker had already spawned yt-dlp for it.
            DownloadBus.remove(id)
            DownloadBus.cancelProcessFor(id)
        }
    }

    fun cancelAll() {
        DownloadBus.cancelQueued()
        queue.value.filter { it.isRunning }.forEach { DownloadBus.requestCancel(it.id) }
        DownloadBus.cancelCurrent()
    }

    fun clearFinished() {
        DownloadBus.clearFinished()
    }

    fun retry(id: String) {
        DownloadBus.update(id) {
            it.copy(
                status = JobStatus.QUEUED,
                phase = JobPhase.PREPARING,
                progress = -1f,
                etaSeconds = -1L,
                itemIndex = 0,
                itemTotal = 0,
                savedCount = 0,
                detail = null,
                note = null,
                caveat = null,
                cancelRequested = false,
                error = null,
                errorKind = null,
            )
        }
        DownloadService.ensureRunning(app)
    }

    fun delete(record: DownloadRecord) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.delete(Uri.parse(record.uri), null, null) }
            }
            history.remove(record.id)
            emit(text(R.string.removed))
        }
    }

    fun clearAll() {
        val snapshot = items.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                snapshot.forEach { record ->
                    runCatching { app.contentResolver.delete(Uri.parse(record.uri), null, null) }
                }
            }
            history.clear()
        }
    }

    fun setFolder(name: String) {
        settings.setFolder(name)
    }

    fun setTrimNonMusic(value: Boolean) {
        settings.setSkipNonMusic(value)
    }

    fun updateEngine() {
        if (_engineStatus.value == EngineStatus.UPDATING) return
        _engineStatus.value = EngineStatus.UPDATING
        viewModelScope.launch {
            val version = YtDlpEngine.updateEngine(app)
            if (version != null) {
                _engineVersion.value = version
                _engineStatus.value = EngineStatus.UPDATED
            } else {
                _engineStatus.value = EngineStatus.FAILED
            }
        }
    }

    /** Reads the engine version for Settings without forcing an update. */
    fun loadEngineVersion() {
        if (_engineVersion.value != null) return
        viewModelScope.launch {
            _engineVersion.value = withContext(Dispatchers.IO) {
                YtDlpEngine.engineVersion(app)
            }
        }
    }

    // ---- Search and playback ----------------------------------------------

    val onlineMode: StateFlow<Boolean> = settings.onlineMode
    val playback: StateFlow<PlaybackState> = PlaybackController.state

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<MediaInfo>>(emptyList())
    val searchResults: StateFlow<List<MediaInfo>> = _searchResults.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    /** The result currently being resolved into a stream URL, if any. */
    private val _preparingTrack = MutableStateFlow<String?>(null)
    val preparingTrack: StateFlow<String?> = _preparingTrack.asStateFlow()

    /** Bumped when a shared link arrives, so the UI can jump to the search tab. */
    private val _focusSearch = MutableStateFlow(0)
    val focusSearch: StateFlow<Int> = _focusSearch.asStateFlow()

    fun onSearchQueryChange(value: String) {
        _searchQuery.value = value
        if (value.isBlank()) {
            _searchResults.value = emptyList()
            _searchError.value = null
        }
    }

    fun setOnlineMode(value: Boolean) {
        settings.setOnlineMode(value)
        _searchResults.value = emptyList()
        _searchError.value = null
    }

    fun requestSearchFocus() {
        _focusSearch.value += 1
    }

    /**
     * The search box accepts three things: a phrase, a video link, or nothing at all.
     * A link resolves to the one video it points at; offline mode never leaves the
     * phone, so it has nothing to ask the network for.
     */
    fun submitSearch() {
        val raw = _searchQuery.value.trim()
        if (raw.isEmpty()) return
        val link = normalize(raw)
        if (link != null && looksLikeVideoLink(raw)) {
            resolveLink(link)
            return
        }
        if (!onlineMode.value || _searching.value) return

        _searching.value = true
        _searchError.value = null
        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    runCatching { YtDlpEngine.search(app, raw, SEARCH_LIMIT) }
                        .getOrDefault(emptyList())
                }
                _searchResults.value = results
                if (results.isEmpty()) _searchError.value = text(R.string.player_search_empty)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (t: Throwable) {
                _searchError.value = text(R.string.player_search_failed)
            } finally {
                _searching.value = false
            }
        }
    }

    private fun resolveLink(link: String) {
        // A bare playlist link is one engine run, not a search result: hand it to the
        // queue the same way the browser's "download whole playlist" button does.
        if (isCollectionLink(link) && !link.contains("v=", ignoreCase = true)) {
            _searchResults.value = emptyList()
            _searchError.value = null
            enqueuePlaylist(link)
            _searchQuery.value = ""
            return
        }
        if (_searching.value) return
        _searching.value = true
        _searchError.value = null
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    runCatching { YtDlpEngine.probeSingle(app, link) }.getOrNull()
                }
                _searchResults.value = listOfNotNull(info)
                if (info == null) _searchError.value = text(R.string.player_search_link_failed)
            } finally {
                _searching.value = false
            }
        }
    }

    /** Queues one search result at the format chosen on the sheet. */
    fun downloadSearchResult(info: MediaInfo, format: AudioFormat) {
        _format.value = format
        enqueue(listOf(queueItem(info.url, format, info)))
        emit(text(R.string.enqueued_one))
    }

    /** Resolves a stream URL and starts playback without storing anything. */
    fun playSearchResult(info: MediaInfo) {
        if (_preparingTrack.value != null) return
        _preparingTrack.value = info.url
        viewModelScope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    runCatching { YtDlpEngine.streamUrl(app, info.url) }.getOrNull()
                }
                if (source == null) {
                    emit(text(R.string.player_play_failed))
                    return@launch
                }
                PlaybackController.playQueue(
                    app,
                    listOf(
                        PlayableTrack(
                            key = info.url,
                            title = info.title,
                            artist = info.uploader,
                            artUrl = info.thumbnailUrl,
                            source = source,
                            isLocal = false,
                            durationMs = info.durationSeconds * 1000L,
                        )
                    ),
                    0,
                )
            } finally {
                _preparingTrack.value = null
            }
        }
    }

    fun playRecords(records: List<DownloadRecord>, startIndex: Int) {
        if (records.isEmpty()) return
        PlaybackController.playQueue(app, records.map(::trackOf), startIndex)
    }

    fun togglePlayback() = PlaybackController.toggle()

    fun nextTrack() = PlaybackController.next(app)

    fun previousTrack() = PlaybackController.previous(app)

    fun seekTo(ms: Long) = PlaybackController.seekTo(ms)

    fun toggleShuffle() = PlaybackController.setShuffle(!playback.value.shuffle)

    fun cycleRepeat() = PlaybackController.cycleRepeat()

    fun playFromQueue(index: Int) = PlaybackController.playFromQueue(index)

    fun removeFromQueue(index: Int) = PlaybackController.removeFromQueue(index)

    fun playRecordNext(record: DownloadRecord) = PlaybackController.playNext(trackOf(record))

    fun addRecordToQueue(record: DownloadRecord) = PlaybackController.addToQueue(trackOf(record))

    // ---- Favourites, queue and sleep timer --------------------------------

    val favorites: StateFlow<Set<String>> = favoritesRepo.keys

    fun toggleFavorite(key: String) {
        favoritesRepo.toggle(key)
    }

    private val _sleepEndsAt = MutableStateFlow<Long?>(null)
    val sleepEndsAt: StateFlow<Long?> = _sleepEndsAt.asStateFlow()

    private var sleepJob: Job? = null

    /** `null` cancels; otherwise pauses playback when the countdown runs out. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepJob = null
        if (minutes == null) {
            _sleepEndsAt.value = null
            return
        }
        val millis = minutes * 60_000L
        _sleepEndsAt.value = System.currentTimeMillis() + millis
        sleepJob = viewModelScope.launch {
            delay(millis)
            if (playback.value.playing) togglePlayback()
            _sleepEndsAt.value = null
            sleepJob = null
            emit(text(R.string.player_sleep_done))
        }
    }

    private fun trackOf(record: DownloadRecord) = PlayableTrack(
        key = record.id,
        title = record.title,
        artist = record.uploader,
        artUrl = record.thumbnailUrl,
        source = record.uri,
        isLocal = true,
        durationMs = record.durationSeconds * 1000L,
    )

    // ---- Signed-in YouTube library ----------------------------------------

    private val _collections = MutableStateFlow<List<CollectionInfo>>(emptyList())
    val collections: StateFlow<List<CollectionInfo>> = _collections.asStateFlow()

    private val _collectionsLoading = MutableStateFlow(false)
    val collectionsLoading: StateFlow<Boolean> = _collectionsLoading.asStateFlow()

    private val _collectionTitle = MutableStateFlow<String?>(null)
    val collectionTitle: StateFlow<String?> = _collectionTitle.asStateFlow()

    private val _collectionTracks = MutableStateFlow<List<MediaInfo>>(emptyList())
    val collectionTracks: StateFlow<List<MediaInfo>> = _collectionTracks.asStateFlow()

    private val _collectionLoading = MutableStateFlow(false)
    val collectionLoading: StateFlow<Boolean> = _collectionLoading.asStateFlow()

    /** Loads the signed-in user's own playlists; cheap to call repeatedly. */
    fun loadCollections(force: Boolean = false) {
        if (!_signedIn.value) {
            _collections.value = emptyList()
            return
        }
        if (_collectionsLoading.value) return
        if (!force && _collections.value.isNotEmpty()) return
        _collectionsLoading.value = true
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    runCatching { YtDlpEngine.tabEntries(app, MY_PLAYLISTS_URL) }
                        .getOrDefault(emptyList())
                }
                _collections.value = entries
                    .map { CollectionInfo(title = it.title, url = it.url) }
                    .distinctBy { it.url }
            } finally {
                _collectionsLoading.value = false
            }
        }
    }

    fun openLikedVideos() = openCollection(text(R.string.player_liked), LIKED_URL)

    fun openWatchLater() = openCollection(text(R.string.player_watch_later), WATCH_LATER_URL)

    /** Opens one playlist natively. The list comes from yt-dlp, never a web page. */
    fun openCollection(title: String, url: String) {
        _collectionTitle.value = title
        _collectionTracks.value = emptyList()
        _collectionLoading.value = true
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    runCatching { YtDlpEngine.tabEntries(app, url, COLLECTION_LIMIT) }
                        .getOrDefault(emptyList())
                }
                _collectionTracks.value = entries
            } finally {
                _collectionLoading.value = false
            }
        }
    }

    fun closeCollection() {
        _collectionTitle.value = null
        _collectionTracks.value = emptyList()
    }

    /** Clears anything that only makes sense while signed in. */
    fun onSignedOut() {
        _signedIn.value = false
        _collections.value = emptyList()
        closeCollection()
    }

    // ---- Local playlists ---------------------------------------------------

    val playlists: StateFlow<List<LocalPlaylist>> = playlistRepo.playlists

    private val _openPlaylistId = MutableStateFlow<String?>(null)
    val openPlaylistId: StateFlow<String?> = _openPlaylistId.asStateFlow()

    fun createPlaylist(name: String): String = playlistRepo.create(name).id

    fun renamePlaylist(id: String, name: String) = playlistRepo.rename(id, name)

    fun deletePlaylist(id: String) {
        playlistRepo.delete(id)
        if (_openPlaylistId.value == id) _openPlaylistId.value = null
    }

    fun openPlaylist(id: String) {
        _openPlaylistId.value = id
    }

    fun closePlaylist() {
        _openPlaylistId.value = null
    }

    fun addToPlaylist(playlistId: String, record: DownloadRecord) {
        playlistRepo.addTrack(playlistId, record.id)
        emit(text(R.string.player_added_to_playlist))
    }

    fun removeFromPlaylist(playlistId: String, recordId: String) {
        playlistRepo.removeTrack(playlistId, recordId)
    }

    // ---- Lyrics ------------------------------------------------------------

    private val _lyrics = MutableStateFlow(LyricsState())
    val lyrics: StateFlow<LyricsState> = _lyrics.asStateFlow()

    fun openLyrics(track: PlayableTrack) {
        _lyrics.value = LyricsState(key = track.key, raw = lyricsRepo.get(track.key))
    }

    fun closeLyrics() {
        _lyrics.value = LyricsState()
    }

    fun saveLyrics(raw: String) {
        val key = _lyrics.value.key ?: return
        lyricsRepo.put(key, raw)
        _lyrics.update { it.copy(raw = raw, error = null) }
        emit(text(R.string.player_lyrics_saved))
    }

    /** The Automatag-style half: ask LRCLIB for the song by its own metadata. */
    fun fetchLyrics(track: PlayableTrack) {
        if (_lyrics.value.loading) return
        _lyrics.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    LyricsClient.fetch(track.artist, track.title, track.durationMs / 1000L)
                }.getOrNull()
            }
            if (raw == null) {
                _lyrics.update {
                    it.copy(loading = false, error = text(R.string.player_lyrics_missing))
                }
            } else {
                lyricsRepo.put(track.key, raw)
                _lyrics.update { it.copy(loading = false, raw = raw, error = null) }
            }
        }
    }

    private fun enqueue(items: List<QueueItem>) {
        if (items.isEmpty()) return
        DownloadBus.enqueue(items)
        DownloadService.ensureRunning(app)
    }

    private fun queueItem(url: String, format: AudioFormat, info: MediaInfo?) = QueueItem(
        id = UUID.randomUUID().toString(),
        url = url,
        format = format,
        title = info?.title.orEmpty(),
        uploader = info?.uploader,
        thumbnailUrl = info?.thumbnailUrl,
        durationSeconds = info?.durationSeconds ?: 0L,
    )

    /**
     * Batch paste has no metadata pass, so a collection link is queued as a playlist
     * job directly. Otherwise `--no-playlist` would quietly fetch only the first
     * video of every playlist URL in the pasted text.
     */
    private fun queueItemFor(url: String, format: AudioFormat): QueueItem =
        if (isCollectionLink(url) && !url.contains("v=", ignoreCase = true)) {
            QueueItem(
                id = UUID.randomUUID().toString(),
                url = url,
                format = format,
                kind = JobKind.PLAYLIST,
            )
        } else {
            queueItem(url, format, null)
        }

    /** Trims punctuation that often trails a link in shared or wrapped text. */
    private fun cleanLink(raw: String): String =
        raw.trimEnd(')', ']', '}', '>', ',', ';', '"', '\'', '.')

    private fun emit(message: String) {
        _messages.tryEmit(message)
    }

    private fun text(resId: Int, vararg args: Any): String = app.getString(resId, *args)

    private fun clipboardText(): String? {
        val clipboard = app.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0)
            ?.coerceToText(app)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /** Adds a scheme when missing and rejects anything that cannot be a link. */
    private fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.contains(' ')) return null
        val withScheme =
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
            else "https://$trimmed"
        val host = runCatching { Uri.parse(withScheme).host }.getOrNull() ?: return null
        if (!host.contains('.')) return null
        return withScheme
    }

    private fun looksLikeVideoLink(raw: String): Boolean {
        val normalized = normalize(raw) ?: return false
        val host = runCatching { Uri.parse(normalized).host }.getOrNull()?.lowercase() ?: return false
        return VIDEO_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun isCollectionLink(raw: String): Boolean {
        val value = raw.lowercase()
        return COLLECTION_MARKERS.any { value.contains(it) }
    }

    private val app: Application get() = getApplication()

    private companion object {
        const val SEARCH_LIMIT = 20
        private const val COLLECTION_LIMIT = 300

        private const val MY_PLAYLISTS_URL = "https://www.youtube.com/feed/playlists"
        private const val LIKED_URL = "https://www.youtube.com/playlist?list=LL"
        private const val WATCH_LATER_URL = "https://www.youtube.com/playlist?list=WL"

        val LINKS = Regex("""https?://\S+""")

        val VIDEO_HOSTS = listOf(
            "youtube.com",
            "youtu.be",
            "vimeo.com",
            "dailymotion.com",
            "soundcloud.com",
        )

        val COLLECTION_MARKERS = listOf(
            "list=",
            "/playlist",
            "/channel/",
            "/user/",
            "/c/",
            "/@",
            "/videos",
            "/sets/",
        )
    }
}

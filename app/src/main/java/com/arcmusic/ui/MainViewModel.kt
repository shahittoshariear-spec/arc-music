package com.arcmusic.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arcmusic.AppIconManager
import com.arcmusic.R
import com.arcmusic.data.AppNightMode
import com.arcmusic.data.AppTheme
import com.arcmusic.data.AudioFormat
import com.arcmusic.data.DownloadRecord
import com.arcmusic.data.FavoritesRepository
import com.arcmusic.data.HistoryRepository
import com.arcmusic.data.LibraryImporter
import com.arcmusic.data.LocalPlaylist
import com.arcmusic.data.LyricsRepository
import com.arcmusic.data.LyricsStyle
import com.arcmusic.data.PlaylistRepository
import com.arcmusic.data.SettingsRepository
import com.arcmusic.data.StatsData
import com.arcmusic.data.StatsRepository
import com.arcmusic.downloader.AutoTag
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
import java.io.File
import java.util.UUID

enum class EngineStatus { IDLE, UPDATING, UPDATED, FAILED }

/** What the search box looks for: individual songs, or whole playlists. */
enum class SearchMode { SONGS, PLAYLISTS, RECOMMENDATIONS }

/** One row in the signed-in library: a saved playlist, or one of the fixed feeds. */
data class CollectionInfo(val title: String, val url: String)

/** A system delete confirmation for a file this app did not create. */
class DeletePrompt(val sender: IntentSender)

/** What the lyrics screen is showing for the current track. */
data class LyricsState(
    val track: PlayableTrack? = null,
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
    private val statsRepo = StatsRepository.get(application)

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _format = settings.downloadFormat
    val format: StateFlow<AudioFormat> = _format
    val autoFormat: StateFlow<Boolean> = settings.autoFormat

    fun setAutoFormat(value: Boolean) = settings.setAutoFormat(value)

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

    /** Playback clock, separate from [playback] so lists do not recompose on every tick. */
    val position: StateFlow<Long> = PlaybackController.position

    /** True once a YouTube session has been captured from the in-app browser. */
    private val _signedIn = MutableStateFlow(CookieStore.pathOrNull(application) != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()
    val folder: StateFlow<String> = settings.folder
    val trimNonMusic: StateFlow<Boolean> = settings.skipNonMusic

    // ---- Appearance -------------------------------------------------------

    val theme: StateFlow<AppTheme> = settings.theme
    val artworkGradient: StateFlow<Boolean> = settings.artworkGradient
    val glassMode: StateFlow<Boolean> = settings.glassMode
    val nightMode: StateFlow<AppNightMode> = settings.nightMode
    val showSearchTab: StateFlow<Boolean> = settings.showSearchTab
    val showBrowseTab: StateFlow<Boolean> = settings.showBrowseTab
    val customAccent: StateFlow<Float> = settings.customAccent
    val customBackground: StateFlow<Float> = settings.customBackground
    val customSurface: StateFlow<Float> = settings.customSurface
    val searchButtonAtBottom: StateFlow<Boolean> = settings.searchButtonAtBottom
    val maxSearchMinutes: StateFlow<Int> = settings.maxSearchMinutes
    val autoTag: StateFlow<Boolean> = settings.autoTag
    val hqArtwork: StateFlow<Boolean> = settings.hqArtwork
    val lyricsStyle: StateFlow<LyricsStyle> = settings.lyricsStyle
    val lyricsTapSeek: StateFlow<Boolean> = settings.lyricsTapSeek

    fun setLyricsStyle(value: LyricsStyle) = settings.setLyricsStyle(value)

    fun setLyricsTapSeek(value: Boolean) = settings.setLyricsTapSeek(value)

    /** Auto(rc) progress, so Settings can show what the last run did. */
    val autoTagStatus: StateFlow<AutoTag.Status> = AutoTag.status

    /** Download format, for the player menu and the auto-format path. */
    val downloadFormat: StateFlow<AudioFormat> = settings.downloadFormat

    fun setTheme(value: AppTheme) {
        settings.setTheme(value)
        // The home-screen icon follows the accent.
        runCatching { AppIconManager.apply(app, value) }
    }

    fun setArtworkGradient(value: Boolean) = settings.setArtworkGradient(value)

    fun setGlassMode(value: Boolean) = settings.setGlassMode(value)

    fun setNightMode(value: AppNightMode) = settings.setNightMode(value)

    fun setShowSearchTab(value: Boolean) = settings.setShowSearchTab(value)

    fun setShowBrowseTab(value: Boolean) = settings.setShowBrowseTab(value)

    fun setHqArtwork(value: Boolean) = settings.setHqArtwork(value)

    fun setCustomAccent(value: Float) = settings.setCustomAccent(value)

    fun setCustomBackground(value: Float) = settings.setCustomBackground(value)

    fun setCustomSurface(value: Float) = settings.setCustomSurface(value)

    fun setSearchButtonAtBottom(value: Boolean) = settings.setSearchButtonAtBottom(value)

    fun setMaxSearchMinutes(value: Int) {
        settings.setMaxSearchMinutes(value)
        // Results already on screen were filtered with the old cap, so the phrase is run
        // again instead of leaving stale long songs in place.
        if (_searchQuery.value.isNotBlank() &&
            _searchResults.value.isNotEmpty()
        ) {
            submitSearch()
        }
    }

    fun setAutoTag(value: Boolean) = settings.setAutoTag(value)

    /** Re-runs Auto(rc) over everything already in the library. */
    fun runAutoTagOnLibrary() {
        AutoTag.enqueue(app, HistoryRepository.get(app).items.value.map { it.id }, force = true)
        emit(text(R.string.player_autotag_queued))
    }

    /** Runs Auto(rc) for a selection made in the library. */
    fun autoTagRecords(records: List<DownloadRecord>) {
        if (records.isEmpty()) return
        AutoTag.enqueue(app, records.map { it.id }, force = true)
        emit(text(R.string.player_autotag_queued))
    }

    fun onUrlChange(value: String) {
        _url.value = value
    }

    fun onFormatChange(value: AudioFormat) {
        settings.setDownloadFormat(value)
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

    /** One video, queued at once: the service fills the title in before it starts. */
    private fun enqueueSingle(link: String) {
        // No probe in front of the queue any more. It used to block the app for a couple
        // of seconds and silently dropped a second share that arrived during it.
        enqueue(
            listOf(
                QueueItem(
                    id = UUID.randomUUID().toString(),
                    url = link,
                    format = _format.value,
                )
            )
        )
        _url.value = ""
        emit(text(R.string.enqueued_one))
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
            val deleted = withContext(Dispatchers.IO) { deleteFile(record.uri) }
            // Files this app did not create can only be removed with the system's
            // confirmation dialog, otherwise the record would vanish and the next
            // folder scan would add it straight back.
            if (deleted || !requestDelete(listOf(record), clearAll = false)) {
                history.remove(record.id)
                emit(text(R.string.removed))
            }
        }
    }

    fun clearAll() {
        val snapshot = items.value
        viewModelScope.launch {
            val refused = withContext(Dispatchers.IO) {
                snapshot.filterNot { record -> deleteFile(record.uri) }
            }
            if (refused.isEmpty() || !requestDelete(refused, clearAll = true)) {
                history.clear()
            }
        }
    }

    /**
     * Queues every library song whose file is gone, using the YouTube link saved with it.
     * This is what makes a restored backup useful: the data survives, the audio can be
     * fetched again.
     */
    fun redownloadMissing() {
        viewModelScope.launch {
            val missing = withContext(Dispatchers.IO) {
                items.value.filter { record ->
                    !record.sourceUrl.isNullOrBlank() && !fileExists(record.uri)
                }
            }
            if (missing.isEmpty()) {
                emit(text(R.string.settings_redownload_none))
                return@launch
            }
            enqueue(
                missing.map { record ->
                    QueueItem(
                        id = UUID.randomUUID().toString(),
                        url = record.sourceUrl.orEmpty(),
                        format = record.format,
                        title = record.title,
                        uploader = record.uploader,
                        thumbnailUrl = record.thumbnailUrl,
                        durationSeconds = record.durationSeconds,
                    )
                }
            )
            emit(text(R.string.settings_redownload_queued, missing.size))
        }
    }

    private fun fileExists(uri: String): Boolean {
        val parsed = Uri.parse(uri)
        return when (parsed.scheme) {
            "content" -> runCatching {
                app.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { true }
            }.getOrNull() == true
            "file" -> File(parsed.path.orEmpty()).isFile
            else -> false
        }
    }

    /** Removes a selection in one go. Doing it record by record used to overwrite the
     * pending delete request each time, so the system dialog only approved the last one.
     */
    fun deleteAll(records: List<DownloadRecord>) {
        if (records.isEmpty()) return
        viewModelScope.launch {
            val refused = withContext(Dispatchers.IO) {
                records.filterNot { record -> deleteFile(record.uri) }
            }
            if (refused.isEmpty() || !requestDelete(refused, clearAll = false)) {
                history.removeAll(records.map { it.id })
                emit(text(R.string.removed))
            }
        }
    }

    /**
     * Removes the bytes behind a record. A scanned file that could not be indexed is
     * stored as file://, which the content resolver cannot delete; unlinking it directly
     * is what stops the next scan from adopting it again.
     */
    private fun deleteFile(uri: String): Boolean {
        val parsed = Uri.parse(uri)
        return when (parsed.scheme) {
            "file" -> runCatching { File(parsed.path.orEmpty()).delete() }.getOrDefault(false)
            "content" -> runCatching { app.contentResolver.delete(parsed, null, null) }.isSuccess
            else -> false
        }
    }

    /** Emitted when the system needs the user to confirm deleting someone else's file. */
    private val _deleteRequests = MutableSharedFlow<DeletePrompt>(extraBufferCapacity = 1)
    val deleteRequests: SharedFlow<DeletePrompt> = _deleteRequests.asSharedFlow()

    private var pendingDeleteIds: List<String> = emptyList()
    private var pendingDeleteClearsAll = false

    private suspend fun requestDelete(
        records: List<DownloadRecord>,
        clearAll: Boolean,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val uris = records
            .map { Uri.parse(it.uri) }
            .filter { it.scheme == "content" }
        if (uris.isEmpty()) return false

        val sender = withContext(Dispatchers.IO) {
            runCatching {
                MediaStore.createDeleteRequest(app.contentResolver, uris).intentSender
            }.getOrNull()
        } ?: return false

        pendingDeleteIds = records.map { it.id }
        pendingDeleteClearsAll = clearAll
        _deleteRequests.tryEmit(DeletePrompt(sender))
        return true
    }

    /** The user agreed to the system's delete dialog. */
    fun onDeleteApproved() {
        val ids = pendingDeleteIds
        val clearAll = pendingDeleteClearsAll
        pendingDeleteIds = emptyList()
        pendingDeleteClearsAll = false
        if (clearAll) {
            history.clear()
        } else if (ids.isNotEmpty()) {
            history.removeAll(ids)
            emit(text(R.string.removed))
        }
    }

    /** The dialog was dismissed; nothing is removed from the library. */
    fun onDeleteDeclined() {
        pendingDeleteIds = emptyList()
        pendingDeleteClearsAll = false
    }

    /**
     * Adopts audio files that were copied into the download folder by something else.
     * Runs when the app comes to the foreground; the importer skips itself when no
     * storage permission has been granted or when a scan ran a moment ago.
     */
    fun syncLibrary(force: Boolean = false) {
        viewModelScope.launch {
            // A scan touches the filesystem and the media database; a failure there must
            // not take the app down with it.
            val imported = runCatching { LibraryImporter.sync(app, force) }.getOrDefault(0)
            if (imported > 0) emit(text(R.string.library_imported, imported))
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

    private val _searchMode = MutableStateFlow(SearchMode.SONGS)
    val searchMode: StateFlow<SearchMode> = _searchMode.asStateFlow()

    /** Switching between songs and playlists re-runs the current phrase. */
    fun setSearchMode(mode: SearchMode) {
        if (_searchMode.value == mode) return
        _searchMode.value = mode
        _searchResults.value = emptyList()
        _searchError.value = null
        if (_searchQuery.value.isNotBlank() || mode == SearchMode.RECOMMENDATIONS) {
            submitSearch()
        }
    }
    val playback: StateFlow<PlaybackState> = PlaybackController.state

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<MediaInfo>>(emptyList())
    val searchResults: StateFlow<List<MediaInfo>> = _searchResults.asStateFlow()

    private data class CachedSearch(val at: Long, val results: List<MediaInfo>)

    /**
     * The engine takes a second or two per query; repeating one within ten minutes is
     * common enough (reopening the tab, flipping to playlists) that the answer is kept.
     * Access-order LRU, main thread only.
     */
    private val searchCache = object : LinkedHashMap<String, CachedSearch>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CachedSearch>?,
        ): Boolean = size > 12
    }

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

    fun requestSearchFocus() {
        _focusSearch.value += 1
    }

    /** Search phrases the box remembers while it is empty. */
    val recentSearches: StateFlow<List<String>> = settings.recentSearches

    fun onRecentSearchClick(query: String) {
        _searchQuery.value = query
    }

    fun forgetSearch(query: String) {
        settings.forgetSearch(query)
    }

    fun clearRecentSearches() {
        settings.clearRecentSearches()
    }

    /**
     * The search box accepts three things: a phrase, a video link, or nothing at all.
     * A link resolves to the one video it points at; a phrase is always a YouTube search.
     */
    fun submitSearch() {
        val raw = _searchQuery.value.trim()
        val link = normalize(raw)
        if (raw.isNotEmpty() && link != null && looksLikeVideoLink(raw)) {
            resolveLink(link)
            return
        }
        if (_searchMode.value == SearchMode.RECOMMENDATIONS) {
            loadRecommendations()
            return
        }
        if (raw.isEmpty()) return
        if (_searching.value) return

        val cacheKey = "${_searchMode.value.name}|${raw.lowercase()}"
        val cached = searchCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.at < SEARCH_CACHE_MS) {
            _searchResults.value = cached.results
            _searchError.value = null
            settings.rememberSearch(raw)
            return
        }

        settings.rememberSearch(raw)
        _searching.value = true
        _searchError.value = null
        _searchResults.value = emptyList()
        viewModelScope.launch {
            try {
                val playlists = _searchMode.value == SearchMode.PLAYLISTS
                val limitMinutes = settings.maxSearchMinutes.value
                // Songs appear as yt-dlp prints them; the final list below replaces the
                // incremental rows with the same data once the process exits.
                val onItem: ((MediaInfo) -> Unit)? = if (playlists) {
                    null
                } else {
                    { info ->
                        if (limitMinutes <= 0 ||
                            info.durationSeconds <= 0L ||
                            info.durationSeconds <= limitMinutes * 60L
                        ) {
                            _searchResults.update { current ->
                                if (current.any { it.url == info.url }) current else current + info
                            }
                        }
                    }
                }
                val results = withContext(Dispatchers.IO) {
                    runCatching {
                        if (playlists) {
                            YtDlpEngine.searchPlaylists(app, raw, SEARCH_LIMIT)
                        } else {
                            YtDlpEngine.search(app, raw, SEARCH_LIMIT, onItem)
                        }
                    }.getOrDefault(emptyList())
                }.filter { info ->
                    // A playlist result carries no duration, so it is never filtered
                    // out; only songs with a known length can break the cap.
                    limitMinutes <= 0 ||
                        info.durationSeconds <= 0L ||
                        info.durationSeconds <= limitMinutes * 60L
                }
                _searchResults.value = results
                if (results.isNotEmpty()) {
                    searchCache[cacheKey] = CachedSearch(System.currentTimeMillis(), results)
                }
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

    /**
     * YouTube's own radio for the song in the player, or the last thing played. It is the
     * same auto-generated list the Music app shows as "start radio", served flat so one
     * extraction fills the page.
     */
    private fun loadRecommendations() {
        if (_searching.value) return
        val url = recommendationSeed()
        if (url == null) {
            _searchError.value = text(R.string.player_recommend_need_history)
            return
        }
        _searching.value = true
        _searchError.value = null
        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    runCatching {
                        YtDlpEngine.tabEntries(app, url, SEARCH_LIMIT)
                    }.getOrDefault(emptyList())
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

    private fun recommendationSeed(): String? {
        val direct = playback.value.current?.key?.let(::videoIdOf)
        if (direct != null) return "https://music.youtube.com/playlist?list=RDAMVM$direct"

        val recent = stats.value.recentlyPlayed(3)
            .asSequence()
            .mapNotNull { videoIdOf(it.key) }
            .firstOrNull()
        if (recent != null) return "https://music.youtube.com/playlist?list=RDAMVM$recent"

        // A downloaded song only carries a content:// id, but its source link still
        // names the video. The newest record is the best available seed.
        val fromLibrary = history.items.value.asSequence()
            .mapNotNull { record -> record.sourceUrl?.let(::videoIdOf) }
            .firstOrNull()
        if (fromLibrary != null) return "https://music.youtube.com/playlist?list=RDAMVM$fromLibrary"

        return if (signedIn.value) "https://www.youtube.com/playlist?list=LL" else null
    }

    private fun videoIdOf(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val match = Regex("(?:[?&]v=|youtu\\.be/|/shorts/)([A-Za-z0-9_-]{11})")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
        if (match != null) return match
        return raw.takeIf {
            it.length == 11 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' }
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
        enqueue(listOf(queueItem(info.url, format, info)))
        emit(text(R.string.enqueued_one))
    }

    /** Downloads the track currently in the player, for the song menu. */
    fun downloadTrack(track: PlayableTrack, format: AudioFormat) {
        if (track.isLocal) return
        val info = MediaInfo(
            url = track.key,
            title = track.title,
            uploader = track.artist,
            thumbnailUrl = track.artUrl,
            durationSeconds = track.durationMs / 1000,
        )
        downloadSearchResult(info, format)
    }

    /** Queues another copy of an already-downloaded song, in a different format. */
    fun downloadRecordAs(record: DownloadRecord, format: AudioFormat) {
        val url = record.sourceUrl
        if (url.isNullOrBlank()) {
            emit(text(R.string.player_no_source))
            return
        }
        val info = MediaInfo(
            url = url,
            title = record.title,
            uploader = record.uploader,
            thumbnailUrl = record.thumbnailUrl,
            durationSeconds = record.durationSeconds,
        )
        downloadSearchResult(info, format)
    }

    /** Resolves a stream URL and starts playback without storing anything. */
    fun playSearchResult(info: MediaInfo) {
        withResolvedTrack(info) { track -> PlaybackController.playTrack(app, track) }
    }

    fun playSearchResultNext(info: MediaInfo) {
        withResolvedTrack(info) { track -> PlaybackController.playNext(track) }
    }

    fun addSearchResultToQueue(info: MediaInfo) {
        withResolvedTrack(info) { track ->
            PlaybackController.addToQueue(track)
            emit(text(R.string.player_added_to_queue))
        }
    }

    /**
     * Streaming needs a direct URL, which yt-dlp resolves in a second or two. Everything
     * that acts on a search result goes through here so there is one place that handles
     * the spinner and the failure path.
     */
    private fun withResolvedTrack(info: MediaInfo, action: (PlayableTrack) -> Unit) {
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
                action(
                    PlayableTrack(
                        key = info.url,
                        title = info.title,
                        artist = info.uploader,
                        artUrl = info.thumbnailUrl,
                        source = source,
                        isLocal = false,
                        durationMs = info.durationSeconds * 1000L,
                    )
                )
            } finally {
                _preparingTrack.value = null
            }
        }
    }

    /** Deletes the saved copy behind a search result, if there is one. */
    fun removeDownloadFor(sourceUrl: String) {
        val record = items.value.firstOrNull { it.sourceUrl == sourceUrl } ?: return
        delete(record)
    }

    fun playRecords(records: List<DownloadRecord>, startIndex: Int) {
        if (records.isEmpty()) return
        PlaybackController.playQueue(app, records.map(::trackOf), startIndex)
    }

    fun togglePlayback() = PlaybackController.toggle()

    /** Ends playback and clears the queue, used when the mini player is swiped away. */
    fun stopPlayback() = PlaybackController.stop()

    fun nextTrack() = PlaybackController.next(app)

    fun previousTrack() = PlaybackController.previous(app)

    fun seekTo(ms: Long) = PlaybackController.seekTo(ms)

    fun toggleShuffle() = PlaybackController.setShuffle(!playback.value.shuffle)

    fun cycleRepeat() = PlaybackController.cycleRepeat()

    fun playFromQueue(index: Int) = PlaybackController.playFromQueue(index)

    fun removeFromQueue(index: Int) = PlaybackController.removeFromQueue(index)

    fun moveQueueItem(from: Int, to: Int) = PlaybackController.moveQueueItem(from, to)

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

    /** The link behind the open collection, so it can be downloaded as a whole. */
    private val _collectionUrl = MutableStateFlow<String?>(null)
    val collectionUrl: StateFlow<String?> = _collectionUrl.asStateFlow()

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
        _collectionUrl.value = url
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
        _collectionUrl.value = null
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

    /** Listening history for the library counts and the Stats tab. */
    val stats: StateFlow<StatsData> = statsRepo.state

    /** Clears the play counts and graphs; the downloads and the sign-in stay put. */
    fun resetStats() {
        statsRepo.clear()
        emit(text(R.string.player_stats_reset_done))
    }

    private val _lyrics = MutableStateFlow(LyricsState())
    val lyrics: StateFlow<LyricsState> = _lyrics.asStateFlow()

    fun openLyrics(track: PlayableTrack) {
        _lyrics.value = LyricsState(
            track = track,
            key = track.key,
            raw = lyricsRepo.get(track.key),
        )
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

    /** Queues a whole playlist from the collection screen as one engine run. */
    fun downloadPlaylist(url: String, format: AudioFormat = settings.downloadFormat.value) {
        enqueue(
            listOf(
                QueueItem(
                    id = UUID.randomUUID().toString(),
                    url = url,
                    format = format,
                    kind = JobKind.PLAYLIST,
                )
            )
        )
        emit(text(R.string.enqueued_one))
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
        private const val SEARCH_CACHE_MS = 10 * 60 * 1000L
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

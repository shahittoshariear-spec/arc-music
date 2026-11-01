package com.arcmusic.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences: where files land, and whether to trim non-music sections.
 *
 * A process-wide singleton so the service reads (and sees changes to) the same values
 * the settings UI writes.
 */
class SettingsRepository private constructor(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _folder = MutableStateFlow(
        prefs.getString(KEY_FOLDER, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_FOLDER
    )
    val folder: StateFlow<String> = _folder.asStateFlow()

    private val _skipNonMusic = MutableStateFlow(prefs.getBoolean(KEY_SKIP_NON_MUSIC, false))
    val skipNonMusic: StateFlow<Boolean> = _skipNonMusic.asStateFlow()

    /** Online mode turns the search box into a YouTube search; offline searches the library. */
    private val _onlineMode = MutableStateFlow(prefs.getBoolean(KEY_ONLINE, true))
    val onlineMode: StateFlow<Boolean> = _onlineMode.asStateFlow()

    /** The last few search phrases, newest first, shown while the box is empty. */
    private val _recentSearches = MutableStateFlow(readRecentSearches())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    /** Accent palette. */
    private val _theme = MutableStateFlow(readTheme())
    val theme: StateFlow<AppTheme> = _theme.asStateFlow()

    /**
     * Themes were renamed with the modern icon family; a value written by an older
     * build maps onto its closest survivor instead of silently resetting to the default.
     */
    private fun readTheme(): AppTheme {
        val raw = prefs.getString(KEY_THEME, null).orEmpty()
        runCatching { AppTheme.valueOf(raw) }.getOrNull()?.let { return it }
        val migrated = when (raw) {
            "ROSE", "PINK" -> AppTheme.CRIMSON
            "BLUE", "INDIGO" -> AppTheme.OCEAN
            "GREEN", "LIME" -> AppTheme.EMERALD
            "AMBER" -> AppTheme.SUNSET
            "VIOLET" -> AppTheme.VIOLET
            "GRAPHITE" -> AppTheme.GRAPHITE
            "P3R" -> AppTheme.P3R
            "CWW" -> AppTheme.CELESTIAL
            "CUSTOM" -> AppTheme.CUSTOM
            else -> AppTheme.CRIMSON
        }
        if (raw.isNotEmpty()) prefs.edit().putString(KEY_THEME, migrated.name).apply()
        return migrated
    }

    /** Tints the background with the colours of the playing artwork. */
    private val _artworkGradient = MutableStateFlow(prefs.getBoolean(KEY_GRADIENT, true))
    val artworkGradient: StateFlow<Boolean> = _artworkGradient.asStateFlow()

    /** Frosted, see-through panels instead of solid cards. */
    private val _glassMode = MutableStateFlow(prefs.getBoolean(KEY_GLASS, true))
    val glassMode: StateFlow<Boolean> = _glassMode.asStateFlow()

    /** Light, dark, or whatever the phone is set to. */
    private val _nightMode = MutableStateFlow(
        runCatching { AppNightMode.valueOf(prefs.getString(KEY_NIGHT_MODE, null).orEmpty()) }
            .getOrDefault(AppNightMode.SYSTEM)
    )
    val nightMode: StateFlow<AppNightMode> = _nightMode.asStateFlow()

    /** Whether the Search tab and the library's search UI are shown at all. */
    private val _showSearchTab = MutableStateFlow(prefs.getBoolean(KEY_SHOW_SEARCH, true))
    val showSearchTab: StateFlow<Boolean> = _showSearchTab.asStateFlow()

    /** Whether the Browse tab appears in the pill. */
    private val _showBrowseTab = MutableStateFlow(prefs.getBoolean(KEY_SHOW_BROWSE, true))
    val showBrowseTab: StateFlow<Boolean> = _showBrowseTab.asStateFlow()

    fun setNightMode(value: AppNightMode) {
        prefs.edit().putString(KEY_NIGHT_MODE, value.name).apply()
        _nightMode.value = value
    }

    fun setShowSearchTab(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SEARCH, value).apply()
        _showSearchTab.value = value
    }

    fun setShowBrowseTab(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_BROWSE, value).apply()
        _showBrowseTab.value = value
    }

    /** Auto(rc): fills in tags, cover art and lyrics after every download. */
    private val _autoTag = MutableStateFlow(prefs.getBoolean(KEY_AUTO_TAG, true))
    val autoTag: StateFlow<Boolean> = _autoTag.asStateFlow()

    /**
     * Keeps the largest cover available instead of the lightweight one. Costs a little
     * storage and bandwidth per song; blurry covers on a big screen are the alternative.
     */
    private val _hqArtwork = MutableStateFlow(prefs.getBoolean(KEY_HQ_ART, true))
    val hqArtwork: StateFlow<Boolean> = _hqArtwork.asStateFlow()

    /** Shuffle and repeat outlive the playback session, and the process itself. */
    private val _playbackShuffle = MutableStateFlow(prefs.getBoolean(KEY_PLAYBACK_SHUFFLE, false))
    val playbackShuffle: StateFlow<Boolean> = _playbackShuffle.asStateFlow()

    private val _playbackRepeat = MutableStateFlow(prefs.getInt(KEY_PLAYBACK_REPEAT, 0))
    val playbackRepeat: StateFlow<Int> = _playbackRepeat.asStateFlow()

    fun setFolder(raw: String) {
        val clean = sanitize(raw)
        prefs.edit().putString(KEY_FOLDER, clean).apply()
        _folder.value = clean
    }

    fun setSkipNonMusic(value: Boolean) {
        prefs.edit().putBoolean(KEY_SKIP_NON_MUSIC, value).apply()
        _skipNonMusic.value = value
    }

    fun setOnlineMode(value: Boolean) {
        prefs.edit().putBoolean(KEY_ONLINE, value).apply()
        _onlineMode.value = value
    }

    fun setTheme(value: AppTheme) {
        prefs.edit().putString(KEY_THEME, value.name).apply()
        _theme.value = value
    }

    fun setArtworkGradient(value: Boolean) {
        prefs.edit().putBoolean(KEY_GRADIENT, value).apply()
        _artworkGradient.value = value
    }

    fun setGlassMode(value: Boolean) {
        prefs.edit().putBoolean(KEY_GLASS, value).apply()
        _glassMode.value = value
    }

    /** Hue (0–360) of the accent for the custom theme. */
    private val _customAccent = MutableStateFlow(prefs.getInt(KEY_ACCENT_HUE, 348).toFloat())
    val customAccent: StateFlow<Float> = _customAccent.asStateFlow()

    private val _customBackground = MutableStateFlow(prefs.getInt(KEY_BACKGROUND_HUE, 348).toFloat())
    val customBackground: StateFlow<Float> = _customBackground.asStateFlow()

    private val _customSurface = MutableStateFlow(prefs.getInt(KEY_SURFACE_HUE, 348).toFloat())
    val customSurface: StateFlow<Float> = _customSurface.asStateFlow()

    fun setCustomAccent(hue: Float) = setHue(KEY_ACCENT_HUE, _customAccent, hue)

    fun setCustomBackground(hue: Float) = setHue(KEY_BACKGROUND_HUE, _customBackground, hue)

    fun setCustomSurface(hue: Float) = setHue(KEY_SURFACE_HUE, _customSurface, hue)

    private fun setHue(key: String, target: MutableStateFlow<Float>, hue: Float) {
        val clean = hue.coerceIn(0f, 360f)
        prefs.edit().putInt(key, clean.toInt()).apply()
        target.value = clean
    }

    /** Where the library's search button floats. */
    private val _searchButtonAtBottom = MutableStateFlow(prefs.getBoolean(KEY_SEARCH_BOTTOM, true))
    val searchButtonAtBottom: StateFlow<Boolean> = _searchButtonAtBottom.asStateFlow()

    fun setSearchButtonAtBottom(value: Boolean) {
        prefs.edit().putBoolean(KEY_SEARCH_BOTTOM, value).apply()
        _searchButtonAtBottom.value = value
    }

    /** Longest song an online search may return, in minutes. 0 means no limit. */
    private val _maxSearchMinutes = MutableStateFlow(prefs.getInt(KEY_MAX_MINUTES, 0))
    val maxSearchMinutes: StateFlow<Int> = _maxSearchMinutes.asStateFlow()

    fun setMaxSearchMinutes(value: Int) {
        val clean = value.coerceAtLeast(0)
        prefs.edit().putInt(KEY_MAX_MINUTES, clean).apply()
        _maxSearchMinutes.value = clean
    }

    fun setAutoTag(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_TAG, value).apply()
        _autoTag.value = value
    }

    fun setHqArtwork(value: Boolean) {
        prefs.edit().putBoolean(KEY_HQ_ART, value).apply()
        _hqArtwork.value = value
    }

    fun setPlaybackShuffle(value: Boolean) {
        prefs.edit().putBoolean(KEY_PLAYBACK_SHUFFLE, value).apply()
        _playbackShuffle.value = value
    }

    fun setPlaybackRepeat(value: Int) {
        prefs.edit().putInt(KEY_PLAYBACK_REPEAT, value).apply()
        _playbackRepeat.value = value
    }

    /** How the lyrics screen renders. */
    private val _lyricsStyle = MutableStateFlow(
        runCatching { LyricsStyle.valueOf(prefs.getString(KEY_LYRICS_STYLE, null).orEmpty()) }
            .getOrDefault(LyricsStyle.CLASSIC)
    )
    val lyricsStyle: StateFlow<LyricsStyle> = _lyricsStyle.asStateFlow()

    /** Tapping a lyric line seeks to that moment; some people only want to read. */
    private val _lyricsTapSeek = MutableStateFlow(prefs.getBoolean(KEY_LYRICS_TAP_SEEK, true))
    val lyricsTapSeek: StateFlow<Boolean> = _lyricsTapSeek.asStateFlow()

    fun setLyricsTapSeek(value: Boolean) {
        prefs.edit().putBoolean(KEY_LYRICS_TAP_SEEK, value).apply()
        _lyricsTapSeek.value = value
    }

    fun setLyricsStyle(value: LyricsStyle) {
        prefs.edit().putString(KEY_LYRICS_STYLE, value.name).apply()
        _lyricsStyle.value = value
    }

    /** The format downloads use when Auto download format is on. */
    private val _downloadFormat = MutableStateFlow(
        runCatching { AudioFormat.valueOf(prefs.getString(KEY_FORMAT, null).orEmpty()) }
            .getOrDefault(AudioFormat.MP3)
    )
    val downloadFormat: StateFlow<AudioFormat> = _downloadFormat.asStateFlow()

    /** When on, tapping download never asks for a format. */
    private val _autoFormat = MutableStateFlow(prefs.getBoolean(KEY_AUTO_FORMAT, true))
    val autoFormat: StateFlow<Boolean> = _autoFormat.asStateFlow()

    fun setDownloadFormat(value: AudioFormat) {
        prefs.edit().putString(KEY_FORMAT, value.name).apply()
        _downloadFormat.value = value
    }

    fun setAutoFormat(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_FORMAT, value).apply()
        _autoFormat.value = value
    }

    fun rememberSearch(query: String) {
        val clean = query.trim()
        if (clean.length < 2) return
        val next = (listOf(clean) + _recentSearches.value.filterNot { it.equals(clean, true) })
            .take(MAX_RECENT_SEARCHES)
        prefs.edit().putString(KEY_RECENT_SEARCHES, next.joinToString("\n")).apply()
        _recentSearches.value = next
    }

    fun forgetSearch(query: String) {
        val next = _recentSearches.value.filterNot { it.equals(query, true) }
        prefs.edit().putString(KEY_RECENT_SEARCHES, next.joinToString("\n")).apply()
        _recentSearches.value = next
    }

    fun clearRecentSearches() {
        prefs.edit().remove(KEY_RECENT_SEARCHES).apply()
        _recentSearches.value = emptyList()
    }

    private fun readRecentSearches(): List<String> = prefs
        .getString(KEY_RECENT_SEARCHES, null)
        ?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toList()
        .orEmpty()

    /**
     * Keeps the value safe to use as a single path segment under `Music/`.
     *
     * Leading/trailing dots and spaces go too: `..` would escape the Music directory,
     * and trailing dots are rejected by some filesystems.
     */
    fun sanitize(raw: String): String {
        val cleaned = raw
            .replace(Regex("""[/\\:*?"<>|]"""), "")
            .replace(Regex("""[\u0000-\u001f]"""), "")
            .trim()
            .trim('.', ' ')
            .take(48)
            .trim('.', ' ')
        return cleaned.ifBlank { DEFAULT_FOLDER }
    }

    companion object {
        const val DEFAULT_FOLDER = "A(rc) Music"
        private const val PREFS = "yt_converter_settings"
        private const val KEY_FOLDER = "folder"
        private const val KEY_SKIP_NON_MUSIC = "skipNonMusic"
        private const val KEY_ONLINE = "onlineMode"
        private const val KEY_RECENT_SEARCHES = "recentSearches"
        private const val KEY_THEME = "theme"
        private const val KEY_GRADIENT = "artworkGradient"
        private const val KEY_GLASS = "glassMode"
        private const val KEY_NIGHT_MODE = "nightMode"
        private const val KEY_SHOW_SEARCH = "showSearchTab"
        private const val KEY_SHOW_BROWSE = "showBrowseTab"
        private const val KEY_ACCENT_HUE = "customAccentHue"
        private const val KEY_BACKGROUND_HUE = "customBackgroundHue"
        private const val KEY_SURFACE_HUE = "customSurfaceHue"
        private const val KEY_SEARCH_BOTTOM = "searchButtonAtBottom"
        private const val KEY_MAX_MINUTES = "maxSearchMinutes"
        private const val KEY_AUTO_TAG = "autoTag"
        private const val KEY_HQ_ART = "hqArtwork"
        private const val KEY_PLAYBACK_SHUFFLE = "playbackShuffle"
        private const val KEY_PLAYBACK_REPEAT = "playbackRepeat"
        private const val KEY_FORMAT = "downloadFormat"
        private const val KEY_AUTO_FORMAT = "autoFormat"
        private const val KEY_LYRICS_STYLE = "lyricsStyle"
        private const val KEY_LYRICS_TAP_SEEK = "lyricsTapSeek"
        private const val MAX_RECENT_SEARCHES = 8

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}

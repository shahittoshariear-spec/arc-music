package com.arcmusic.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.core.content.ContextCompat
import com.arcmusic.data.SettingsRepository
import com.arcmusic.data.StatsRepository
import com.arcmusic.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One playable entry: a saved file on this phone, or a stream resolved from YouTube. */
data class PlayableTrack(
    val key: String,
    val title: String,
    val artist: String?,
    val artUrl: String?,
    /** What the player opens: a content:// URI, a file path, or an https URL. */
    val source: String,
    val isLocal: Boolean,
    val durationMs: Long = 0L,
)

enum class RepeatMode { OFF, ALL, ONE }

data class PlaybackState(
    val current: PlayableTrack? = null,
    val queue: List<PlayableTrack> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val error: String? = null,
) {
    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * The app's single ExoPlayer, plus the state the UI renders.
 *
 * ExoPlayer rather than MediaPlayer because background playback needs a real media
 * session: this object is what [PlaybackService] attaches to, which is what produces
 * the notification, lock-screen and headset controls.
 */
object PlaybackController {

    private const val TICK_MS = 500L

    /**
     * How long a paused app may sit in the background before the player is put away.
     * While the media session service is up the process cannot be frozen, so a queue
     * nobody is listening to would keep the phone awake for nothing.
     */
    private const val IDLE_STOP_MS = 10 * 60 * 1000L

    /** Covers are a few tens of KB; the cap keeps a stray file off the Binder limit. */
    private const val MAX_ARTWORK_BYTES = 512 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /**
     * Playback position, deliberately separate from [state]. A 2 Hz position update in
     * the main state would recompose every screen that reads it; this way only the
     * player and the mini progress bar react to the clock.
     */
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    /** False while the app has no visible UI; the ticker is stopped entirely then. */
    @Volatile
    private var uiVisible = true

    private var appContext: Context? = null
    private var ticker: Job? = null
    private var idleJob: Job? = null

    /** Mirrors the player's timeline so a media id can be resolved back to a track. */
    private val tracks = mutableListOf<PlayableTrack>()

    /** Guards against counting the same start twice (queue set + transition callback). */
    private var lastRecordedKey: String? = null
    private var lastRecordedAt = 0L

    /** The tracks whose artwork has already been embedded in the timeline. */
    private val artworkLoaded = HashSet<String>()

    var player: ExoPlayer? = null
        private set

    fun init(context: Context): ExoPlayer? {
        player?.let { return it }
        val app = context.applicationContext
        appContext = app

        val attributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // A music queue needs seconds of read-ahead, not the fifty the defaults keep.
        // The extra buffer kept memory allocated and the disk or network busy with no
        // audible benefit, and the back buffer is pointless for a playlist that only
        // ever moves forward.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 40_000, 1_000, 2_000)
            .setBackBuffer(0, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val exo = ExoPlayer.Builder(app)
            .setLoadControl(loadControl)
            .build()

        // `true` also hands audio focus to the player, so a call or another app pauses
        // us instead of talking over us.
        exo.setAudioAttributes(attributes, true)
        // Restore the modes before the listener is attached: replaying a stored mode
        // through the callback would write the same value back on every player start.
        val settings = SettingsRepository.get(app)
        exo.shuffleModeEnabled = settings.playbackShuffle.value
        exo.repeatMode = when (settings.playbackRepeat.value) {
            1 -> Player.REPEAT_MODE_ALL
            2 -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publish()
                // The position clock only has a reader while audio is moving.
                syncTicker()
                if (isPlaying) cancelIdleStop() else scheduleIdleStop()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _state.update { it.copy(error = null) }
                }
                publish()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                publish()
                recordPlay(mediaItem?.mediaId)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = publish()

            override fun onRepeatModeChanged(repeatMode: Int) = publish()

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(error = error.message, playing = false) }
            }
        })

        player = exo
        syncTicker()
        return exo
    }

    fun playQueue(context: Context, queue: List<PlayableTrack>, index: Int) {
        val exo = init(context) ?: return
        if (queue.isEmpty()) return

        tracks.clear()
        tracks.addAll(queue)
        // A fresh queue rebuilds every media item, so any artwork attached to the old
        // ones is gone and has to be carried over again for the track that starts.
        artworkLoaded.clear()

        val start = index.coerceIn(0, queue.lastIndex)
        exo.setMediaItems(queue.map { it.toMediaItem() }, start, 0L)

        // The starting cover is read up front. It is one local file, a few milliseconds,
        // and it means the very first notification already carries a picture instead of
        // waiting for the asynchronous pass.
        val firstTrack = queue[start]
        val firstArt = firstTrack.artUrl?.takeIf {
            it.startsWith("file://") || it.startsWith("content://")
        }
        readArtwork(firstArt)?.let { bytes ->
            val item = exo.getMediaItemAt(start)
            val metadata = item.mediaMetadata.buildUpon()
                .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .build()
            runCatching {
                exo.replaceMediaItem(start, item.buildUpon().setMediaMetadata(metadata).build())
                artworkLoaded.add(firstTrack.key)
            }
        }
        exo.prepare()
        exo.playWhenReady = true

        startService(context)
        _state.update { it.copy(error = null, positionMs = 0L) }
        publish()
        recordPlay(queue[start].key)
    }

    fun playTrack(context: Context, track: PlayableTrack) = playQueue(context, listOf(track), 0)

    fun toggle() {
        val exo = player
        if (exo == null) {
            // The player was put away while paused; rebuild it at the same spot.
            restorePlayer()
            return
        }
        if (exo.isPlaying) exo.pause() else exo.playWhenReady = true
    }

    /** Manual skip wraps, so the last track leads back to the first. */
    fun next(context: Context) {
        val exo = player ?: return
        if (exo.hasNextMediaItem()) {
            exo.seekToNextMediaItem()
        } else if (exo.mediaItemCount > 0) {
            exo.seekToDefaultPosition(0)
        }
        exo.playWhenReady = true
    }

    fun previous(context: Context) {
        val exo = player ?: return
        if (exo.currentPosition > 3_000L) {
            exo.seekTo(0L)
            return
        }
        if (exo.hasPreviousMediaItem()) {
            exo.seekToPreviousMediaItem()
        } else if (exo.mediaItemCount > 0) {
            exo.seekToDefaultPosition(exo.mediaItemCount - 1)
        }
        exo.playWhenReady = true
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.coerceAtLeast(0L))
        _position.value = ms.coerceAtLeast(0L)
        _state.update { it.copy(positionMs = ms.coerceAtLeast(0L)) }
    }

    /**
     * Called by the activity. With no visible UI the ticker is stopped rather than
     * slowed: background playback then has no timer wake-ups at all, only the player's
     * own audio buffer schedule.
     */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (visible) {
            cancelIdleStop()
            val exo = player
            if (exo != null) {
                _position.value = exo.currentPosition.coerceAtLeast(0L)
                publish()
            }
        } else {
            scheduleIdleStop()
        }
        syncTicker()
    }

    fun setShuffle(enabled: Boolean) {
        player?.shuffleModeEnabled = enabled
        appContext?.let { SettingsRepository.get(it).setPlaybackShuffle(enabled) }
    }

    fun cycleRepeat() {
        val exo = player ?: return
        val next = when (exo.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        exo.repeatMode = next
        appContext?.let { SettingsRepository.get(it).setPlaybackRepeat(next) }
    }

    fun playNext(track: PlayableTrack) {
        val exo = player ?: return
        val at = (exo.currentMediaItemIndex + 1).coerceIn(0, tracks.size)
        tracks.add(at, track)
        exo.addMediaItem(at, track.toMediaItem())
        publish()
    }

    fun addToQueue(track: PlayableTrack) {
        val exo = player ?: return
        tracks.add(track)
        exo.addMediaItem(track.toMediaItem())
        publish()
    }

    fun playFromQueue(index: Int) {
        val exo = player ?: return
        if (index !in tracks.indices) return
        exo.seekToDefaultPosition(index)
        exo.playWhenReady = true
        publish()
    }

    fun removeFromQueue(index: Int) {
        val exo = player ?: return
        if (index !in tracks.indices) return
        tracks.removeAt(index)
        exo.removeMediaItem(index)
        publish()
    }

    fun stop() {
        cancelIdleStop()
        stopTicker()
        artworkLoaded.clear()
        player?.stop()
        player?.clearMediaItems()
        tracks.clear()
        _state.value = PlaybackState()
        _position.value = 0L
    }

    /**
     * The media session service is gone. Anything still playing keeps its player;
     * a paused one is released, because the next play can rebuild it from [tracks].
     */
    fun onServiceDestroyed() {
        val exo = player ?: return
        if (exo.isPlaying) return
        stopTicker()
        runCatching { exo.stop() }
        runCatching { exo.release() }
        player = null
    }

    /**
     * Rebuilds the player after [onServiceDestroyed]. The queue and the clock are kept
     * in [state], so a pause and later resume lands back where it stopped.
     */
    private fun restorePlayer() {
        val app = appContext ?: return
        if (tracks.isEmpty()) return
        val exo = init(app) ?: return
        val index = _state.value.index.coerceIn(0, tracks.lastIndex)
        val position = _state.value.positionMs
        artworkLoaded.clear()
        exo.setMediaItems(tracks.map { it.toMediaItem() }, index, position)
        exo.prepare()
        exo.playWhenReady = true
        startService(app)
    }

    private fun publish() {
        val exo = player ?: return
        val mediaId = exo.currentMediaItem?.mediaId
        val index = tracks.indexOfFirst { it.key == mediaId }
        val current = tracks.getOrNull(index)
        val duration = exo.duration.takeIf { it > 0L }

        _state.update {
            it.copy(
                current = current ?: it.current,
                queue = tracks.toList(),
                index = index,
                playing = exo.isPlaying,
                buffering = exo.playbackState == Player.STATE_BUFFERING,
                durationMs = duration ?: it.durationMs,
                positionMs = exo.currentPosition.coerceAtLeast(0L),
                shuffle = exo.shuffleModeEnabled,
                repeatMode = exo.repeatMode.toRepeatMode(),
            )
        }
        _position.value = exo.currentPosition.coerceAtLeast(0L)
        attachArtwork(exo)
    }

    /**
     * Puts the cover into the session metadata as bytes rather than a path.
     *
     * A `file://` cover lives in this app's private storage, which SystemUI — and so the
     * lock screen and the OEM "dynamic island" — cannot read. Handing it a path meant
     * those surfaces kept showing whatever artwork they already had while the title moved
     * on. Embedded bytes are the only form every process can render.
     *
     * The next item is prepared as well, so the picture is already in the timeline when
     * the track changes instead of arriving a moment later.
     */
    private fun attachArtwork(exo: ExoPlayer) {
        val index = exo.currentMediaItemIndex
        prepareArtwork(exo, index)
        prepareArtwork(exo, index + 1)
    }

    private fun prepareArtwork(exo: ExoPlayer, position: Int) {
        if (position < 0 || position >= exo.mediaItemCount) return
        val item = exo.getMediaItemAt(position)
        val key = item.mediaId
        if (key.isBlank() || !artworkLoaded.add(key)) return

        val artUrl = item.mediaMetadata.artworkUri?.toString()
        val source = item.localConfiguration?.uri?.toString()
        val title = item.mediaMetadata.title?.toString()
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { artworkBytes(artUrl, source, title) }
                ?: return@launch
            if (position >= exo.mediaItemCount) return@launch
            val current = exo.getMediaItemAt(position)
            if (current.mediaId != key) return@launch
            val metadata = current.mediaMetadata.buildUpon()
                .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .build()
            runCatching {
                exo.replaceMediaItem(
                    position,
                    current.buildUpon().setMediaMetadata(metadata).build(),
                )
            }
        }
    }

    /**
     * In order: the record's cover, the picture written into the audio file by Auto(rc),
     * and finally a generated card. Something always reaches the notification, so a song
     * can no longer show a blank square.
     */
    private fun artworkBytes(artUrl: String?, source: String?, title: String?): ByteArray? {
        readArtwork(artUrl)?.let { return it }
        readEmbedded(source)?.let { return it }
        return placeholderArtwork(title)
    }

    private fun readEmbedded(source: String?): ByteArray? {
        val context = appContext ?: return null
        val uri = source?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        if (uri.scheme != "file" && uri.scheme != "content") return null
        return runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() && it.size <= MAX_ARTWORK_BYTES }
    }

    /** A coloured card with the song's initial, for anything with no picture at all. */
    private fun placeholderArtwork(title: String?): ByteArray? = runCatching {
        val label = title?.trim().orEmpty().ifBlank { "A(rc)" }
        val size = 320
        val bitmap = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        val hue = (label.hashCode().mod(360)).toFloat()
        canvas.drawColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.30f)))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = size * 0.42f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val letter = label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "♪"
        canvas.drawText(
            letter,
            size / 2f,
            size / 2f - (paint.descent() + paint.ascent()) / 2f,
            paint,
        )
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
        bitmap.recycle()
        out.toByteArray()
    }.getOrNull()

    /** Reads a cover, local or remote, with a size cap so a stray file cannot bloat the session. */
    private fun readArtwork(rawUrl: String?): ByteArray? {
        val url = rawUrl?.takeIf { it.isNotBlank() } ?: return null
        val context = appContext ?: return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null

        fun readBounded(open: () -> java.io.InputStream?): ByteArray? = runCatching {
            open()?.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > MAX_ARTWORK_BYTES) return@use null
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }

        return when (uri.scheme) {
            "file", "content" -> readBounded { context.contentResolver.openInputStream(uri) }
            "http", "https" -> readBounded {
                val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection)
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "ArcMusic/1.0")
                connection.inputStream
            }
            else -> null
        }
    }

    /** Starts the clock only when something is actually playing and on screen. */
    private fun syncTicker() {
        val shouldRun = uiVisible && player?.isPlaying == true
        if (!shouldRun) {
            stopTicker()
            return
        }
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val exo = player
                if (exo == null || !exo.isPlaying || !uiVisible) break
                _position.value = exo.currentPosition.coerceAtLeast(0L)
                val duration = exo.duration.takeIf { it > 0L }
                if (duration != null) {
                    _state.update {
                        if (it.durationMs == duration) it else it.copy(durationMs = duration)
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /**
     * Schedules the teardown of a paused, off-screen player. Pausing already releases
     * the audio track; this also drops the foreground service so the OS can freeze the
     * process, which is where a background music session costs its battery.
     */
    private fun scheduleIdleStop() {
        val exo = player ?: return
        if (uiVisible || exo.isPlaying) return
        if (idleJob?.isActive == true) return
        idleJob = scope.launch {
            delay(IDLE_STOP_MS)
            if (uiVisible || player?.isPlaying == true) return@launch
            appContext?.let { app ->
                runCatching { app.stopService(Intent(app, PlaybackService::class.java)) }
            }
        }
    }

    private fun cancelIdleStop() {
        idleJob?.cancel()
        idleJob = null
    }

    private fun recordPlay(key: String?) {
        val track = tracks.firstOrNull { it.key == key } ?: return
        val context = appContext ?: return
        val now = System.currentTimeMillis()
        if (key == lastRecordedKey && now - lastRecordedAt < 3_000L) return
        lastRecordedKey = key
        lastRecordedAt = now
        StatsRepository.get(context).record(
            track.key,
            track.title,
            track.artist,
            track.artUrl,
            track.durationMs / 1000L,
        )
    }

    private fun startService(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, PlaybackService::class.java)
        // A plain start carries no five-second foreground deadline, so a notification
        // that is slow to appear can never cost the app its life — several skins kill
        // the process when startForegroundService is used and the notification is late.
        // The service still promotes itself in onStartCommand.
        runCatching { app.startService(intent) }
            .recoverCatching { ContextCompat.startForegroundService(app, intent) }
    }

    private fun PlayableTrack.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(key)
            .setUri(source)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(artUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .setIsPlayable(true)
                    .build()
            )
            .build()

    private fun Int.toRepeatMode(): RepeatMode = when (this) {
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        else -> RepeatMode.OFF
    }
}

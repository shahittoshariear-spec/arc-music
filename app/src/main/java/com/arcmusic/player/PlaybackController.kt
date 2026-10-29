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
import androidx.media3.exoplayer.ExoPlayer
import com.arcmusic.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var ticker: Job? = null

    /** Mirrors the player's timeline so a media id can be resolved back to a track. */
    private val tracks = mutableListOf<PlayableTrack>()

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

        val exo = ExoPlayer.Builder(app).build().apply {
            // `true` also hands audio focus to the player, so a call or another app
            // pauses us instead of talking over us.
            setAudioAttributes(attributes, true)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        _state.update { it.copy(error = null) }
                    }
                    publish()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publish()

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = publish()

                override fun onRepeatModeChanged(repeatMode: Int) = publish()

                override fun onPlayerError(error: PlaybackException) {
                    _state.update { it.copy(error = error.message, playing = false) }
                }
            })
        }

        player = exo
        startTicker()
        return exo
    }

    fun playQueue(context: Context, queue: List<PlayableTrack>, index: Int) {
        val exo = init(context) ?: return
        if (queue.isEmpty()) return

        tracks.clear()
        tracks.addAll(queue)

        val start = index.coerceIn(0, queue.lastIndex)
        exo.setMediaItems(queue.map { it.toMediaItem() }, start, 0L)
        exo.prepare()
        exo.playWhenReady = true

        startService(context)
        _state.update { it.copy(error = null, positionMs = 0L) }
        publish()
    }

    fun playTrack(context: Context, track: PlayableTrack) = playQueue(context, listOf(track), 0)

    fun toggle() {
        val exo = player ?: return
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
        _state.update { it.copy(positionMs = ms.coerceAtLeast(0L)) }
    }

    fun setShuffle(enabled: Boolean) {
        player?.shuffleModeEnabled = enabled
    }

    fun cycleRepeat() {
        val exo = player ?: return
        exo.repeatMode = when (exo.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
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
        player?.stop()
        player?.clearMediaItems()
        tracks.clear()
        _state.value = PlaybackState()
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
                shuffle = exo.shuffleModeEnabled,
                repeatMode = exo.repeatMode.toRepeatMode(),
            )
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val exo = player
                if (exo != null && exo.isPlaying) {
                    val position = exo.currentPosition.coerceAtLeast(0L)
                    val duration = exo.duration.takeIf { it > 0L } ?: 0L
                    _state.update {
                        it.copy(
                            positionMs = position,
                            durationMs = if (duration > 0L) duration else it.durationMs,
                        )
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    private fun startService(context: Context) {
        val app = context.applicationContext
        runCatching {
            app.startService(Intent(app, PlaybackService::class.java))
        }
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

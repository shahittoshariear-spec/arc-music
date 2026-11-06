package com.arcmusic.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import com.arcmusic.MainActivity
import com.arcmusic.R
import com.arcmusic.data.SettingsRepository
import com.arcmusic.player.PlaybackController
import com.arcmusic.player.PlayableTrack
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * Keeps playback alive outside the UI. This is what produces the media notification,
 * the lock-screen controls and the headset buttons — none of which a bare MediaPlayer
 * in the activity can offer.
 *
 * The player itself is a process-wide singleton, so the service can come and go
 * without interrupting the audio.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastArtKey: String? = null
    private var lastArt: Bitmap? = null
    private var logoBitmap: Bitmap? = null

    /** Set once the service has taken the foreground, so it is only posted once. */
    private var foregroundShown = false

    override fun onCreate() {
        super.onCreate()
        runCatching { PlaybackController.init(applicationContext) }
        // The alias should already be right, but re-applying here means a theme change
        // and a service start can never leave the media surfaces on an older icon.
        runCatching {
            val settings = SettingsRepository.get(this)
            com.arcmusic.AppIconManager.apply(this, settings.theme.value, settings.customAccent.value)
        }
        val player = PlaybackController.player
        if (player == null) {
            stopSelf()
            return
        }

        // Nothing in here may take the service down: the player is a process-wide
        // singleton, so audio keeps working even if the notification side fails.
        runCatching { createChannel() }
        // A media card from an older build can outlive the update on some skins; the
        // artwork cache belongs to it, so it is removed rather than refreshed.
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.cancel(LEGACY_MEDIA_NOTIFICATION_ID)
        }
        runCatching {
            // The themed provider below owns the notification. Media3's default one
            // carried neither the accent colour nor the theme icon, and on ColorOS and
            // HyperOS the stale rose badge survived updates because SystemUI kept the
            // first colour it ever saw for this package.
            setMediaNotificationProvider(ThemedProvider())
        }

        session = runCatching {
            MediaSession.Builder(this, player)
                .setSessionActivity(openApp())
                .build()
        }.getOrNull()

        // Keep the foreground notification in step with the player. Relying on Media3
        // alone left some skins (MIUI/HyperOS) showing the old cover and some (EMUI)
        // without any transport buttons; posting the same id with explicit actions and
        // the current artwork fixes both without a second notification.
        scope.launch {
            PlaybackController.state
                .map { state -> Triple(state.current, state.playing, state.current?.key) }
                .distinctUntilChangedBy { it.third to it.second }
                .collect { (track, playing, key) ->
                    if (key != lastArtKey) {
                        lastArt = withContext(Dispatchers.IO) { loadArtwork(track?.artUrl) }
                        lastArtKey = key
                    }
                    postNotification(track, playing, lastArt)
                }
        }
    }

    /**
     * Creates the playback channel before Media3 does.
     *
     * Media3 opens its channel at low importance. MIUI and HyperOS treat low-importance
     * notifications as silent extras: they are collapsed in the shade and kept off the
     * lock screen, so the player appears to have no notification at all, and their
     * dynamic island has no media notification to mirror. Creating the same channel id
     * first, at default importance but silent and public, keeps it on the lock screen.
     * Importance cannot be changed once a channel exists, which is why this has to run
     * before the first notification is posted.
     */
    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_media_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notif_media_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Media3 only posts its notification from a player event, and the service is started
     * after playback has already begun, so on some skins that event never arrives and no
     * media notification is ever created. Posting a media-styled notification here
     * guarantees the foreground notification exists from the first moment; Media3 then
     * replaces it in place, because it uses the same id and channel.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREVIOUS -> PlaybackController.previous(this)
            ACTION_NEXT -> PlaybackController.next(this)
            ACTION_TOGGLE -> PlaybackController.toggle()
        }
        showForeground()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun showForeground() {
        foregroundShown = true
        val state = PlaybackController.state.value
        postNotification(state.current, state.playing, lastArt)
    }

    private fun postNotification(
        track: PlayableTrack?,
        playing: Boolean,
        artwork: Bitmap?,
    ) {
        val builder = notificationBuilder(session, track, playing, artwork)
        val posted = runCatching {
            startForeground(MEDIA_NOTIFICATION_ID, builder.build())
        }.isSuccess
        if (!posted) {
            // Leaving the foreground promise unmet is what the system answers with a
            // kill, so step aside instead. Audio lives in the process, not here.
            stopSelf()
        }
    }

    /**
     * The one notification layout, used both by the foreground post and by Media3's
     * provider so a state change can never swap in a differently coloured copy.
     */
    private fun notificationBuilder(
        mediaSession: MediaSession?,
        track: PlayableTrack?,
        playing: Boolean,
        artwork: Bitmap?,
    ): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_arc)
            .setContentTitle(track?.title ?: getString(R.string.app_name))
            .setContentText(track?.artist ?: getString(R.string.notif_media_starting))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (artwork != null) {
            builder.setLargeIcon(artwork)
        } else {
            neutralLogo()?.let { builder.setLargeIcon(it) }
        }
        // Without a session this is still a valid notification, which keeps the
        // foreground promise on a device where the session could not be built.
        mediaSession?.let { current ->
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(current)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            builder.addAction(
                R.drawable.ic_skip_previous,
                getString(R.string.player_previous),
                serviceAction(1, ACTION_PREVIOUS),
            )
            builder.addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.player_pause else R.string.player_play),
                serviceAction(2, ACTION_TOGGLE),
            )
            builder.addAction(
                R.drawable.ic_skip_next,
                getString(R.string.player_next),
                serviceAction(3, ACTION_NEXT),
            )
        }
        return builder
    }

    /**
     * Media3's provider, replaced with the app's own layout so the accent colour and
     * theme logo reach the lock screen, the shade and the OEM "dynamic island".
     */
    private inner class ThemedProvider : MediaNotification.Provider {
        override fun createNotification(
            mediaSession: MediaSession,
            customLayout: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback,
        ): MediaNotification {
            val state = PlaybackController.state.value
            val track = state.current
            val art = lastArt?.takeIf { track != null && lastArtKey == track.key }
                ?: neutralLogo()
            val notification = notificationBuilder(mediaSession, track, state.playing, art).build()
            return MediaNotification(MEDIA_NOTIFICATION_ID, notification)
        }

        override fun handleCustomCommand(
            mediaSession: MediaSession,
            action: String,
            extras: Bundle,
        ): Boolean = false
    }

    private fun serviceAction(code: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            code,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * A neutral tile used until the cover has been decoded.
     *
     * It deliberately carries no theme colour and no theme logo: every coloured or
     * branded fallback tried so far ended up cached by an OEM media panel as a stale
     * rose badge. A grey card with the app name replaces it within a moment anyway.
     */
    private fun neutralLogo(): Bitmap? {
        logoBitmap?.let { return it }
        val bitmap = runCatching {
            val size = 256
            val image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(image)
            canvas.drawColor(0xFF1B1D22.toInt())
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFEDEFF3.toInt()
                textSize = size * 0.26f
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD,
                )
            }
            canvas.drawText(
                "A(rc)",
                size / 2f,
                size / 2f - (paint.descent() + paint.ascent()) / 2f,
                paint,
            )
            image
        }.getOrNull()
        logoBitmap = bitmap
        return bitmap
    }

    private fun loadArtwork(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val bytes = when {
                url.startsWith("file://") -> File(Uri.parse(url).path.orEmpty()).readBytes()
                url.startsWith("content://") -> contentResolver
                    .openInputStream(Uri.parse(url))
                    ?.use { it.readBytes() }
                else -> URL(url).openStream().use { it.readBytes() }
            } ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) {
                sample *= 2
            }
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away keeps the music going, which is what a music app should do. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = PlaybackController.player
        if (player == null || !player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        // The player outlives a running service on purpose; only the session is released.
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
        // Released after Media3's own teardown: doing it first let its cleanup touch a
        // player that no longer existed.
        PlaybackController.onServiceDestroyed()
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val CHANNEL_ID = "playback"

        /**
         * Distinct from the download service's 1001/1002, and bumped from 2001 so every
         * OEM that caches notification artwork by id starts from a clean slate.
         */
        const val MEDIA_NOTIFICATION_ID = 2002

        /** The id used up to 1.32; cancelled once so no stale media card survives. */
        const val LEGACY_MEDIA_NOTIFICATION_ID = 2001

        const val ACTION_PREVIOUS = "com.arcmusic.action.PREVIOUS"
        const val ACTION_TOGGLE = "com.arcmusic.action.TOGGLE"
        const val ACTION_NEXT = "com.arcmusic.action.NEXT"
    }
}

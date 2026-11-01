package com.arcmusic.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import com.arcmusic.MainActivity
import com.arcmusic.R
import com.arcmusic.player.PlaybackController
import com.arcmusic.player.PlayableTrack

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

    /** Set once the service has taken the foreground, so it is only posted once. */
    private var foregroundShown = false

    override fun onCreate() {
        super.onCreate()
        runCatching { PlaybackController.init(applicationContext) }
        val player = PlaybackController.player
        if (player == null) {
            stopSelf()
            return
        }

        // Nothing in here may take the service down: the player is a process-wide
        // singleton, so audio keeps working even if the notification side fails.
        runCatching { createChannel() }
        runCatching {
            setMediaNotificationProvider(
                DefaultMediaNotificationProvider.Builder(this)
                    // Media3 defaults to notification id 1001, which the download service
                    // also uses: whichever posted last took the other's notification away,
                    // so a download could silence the player for good.
                    .setNotificationId(MEDIA_NOTIFICATION_ID)
                    .setChannelId(CHANNEL_ID)
                    .setChannelName(R.string.notif_media_channel)
                    .build()
                    .also { provider ->
                        // Media3 ships a generic play glyph; the app's own mark makes the
                        // status bar and lock-screen badge recognisable.
                        provider.setSmallIcon(R.drawable.ic_stat_arc)
                    },
            )
        }

        session = runCatching {
            MediaSession.Builder(this, player)
                .setSessionActivity(openApp())
                .build()
        }.getOrNull()
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
        showForeground()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun showForeground() {
        if (foregroundShown) return
        foregroundShown = true
        val track: PlayableTrack? = PlaybackController.state.value.current
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_arc)
            .setContentTitle(track?.title ?: getString(R.string.app_name))
            .setContentText(track?.artist ?: getString(R.string.notif_media_starting))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        // Without a session this is still a valid notification, which keeps the
        // foreground promise on a device where the session could not be built.
        session?.let { builder.setStyle(MediaStyleNotificationHelper.MediaStyle(it)) }

        val posted = runCatching {
            startForeground(MEDIA_NOTIFICATION_ID, builder.build())
        }.isSuccess
        if (!posted) {
            // Leaving the foreground promise unmet is what the system answers with a
            // kill, so step aside instead. Audio lives in the process, not here.
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away keeps the music going, which is what a music app should do. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = PlaybackController.player
        if (player == null || !player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        // The player outlives a running service on purpose; only the session is released.
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

        /** Distinct from the download service's 1001/1002, which it must not replace. */
        const val MEDIA_NOTIFICATION_ID = 2001
    }
}

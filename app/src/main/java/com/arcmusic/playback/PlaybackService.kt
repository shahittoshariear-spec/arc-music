package com.arcmusic.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.arcmusic.MainActivity
import com.arcmusic.player.PlaybackController

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

    override fun onCreate() {
        super.onCreate()
        PlaybackController.init(applicationContext)
        val player = PlaybackController.player ?: return
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away keeps the music going, which is what a music app should do. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = PlaybackController.player
        if (player == null || !player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        // The player outlives the service on purpose; only the session is released.
        session?.release()
        session = null
        super.onDestroy()
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
}

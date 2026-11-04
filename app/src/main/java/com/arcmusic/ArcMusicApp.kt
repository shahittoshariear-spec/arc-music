package com.arcmusic

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.arcmusic.data.SettingsRepository
import com.arcmusic.downloader.AutoTag
import com.arcmusic.player.PlaybackController

class ArcMusicApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // A crash log on disk is the only way a report from another phone can be
        // diagnosed properly; it stays local until the user saves it.
        CrashLog.install(this)
        // The player is created on the first play, not here: opening the app to browse
        // the library should not allocate an audio pipeline and its threads.
        PlaybackController.prime(this)
        // Picks up any Auto(rc) work a previous session could not finish.
        AutoTag.init(this)
        // The launcher alias has to match the saved accent, including after an update.
        runCatching { AppIconManager.apply(this, SettingsRepository.get(this).theme.value) }

        // The Python runtime is not started here any more. It cost every launch a few
        // seconds of CPU and a resident interpreter even for people who only listen to
        // downloaded music; the search tab warms it up instead, which is the first place
        // it can be needed.
    }

    /**
     * Coil 3 keeps networking in a separate artifact, so the fetcher has to be
     * registered explicitly. Thumbnails then cache to disk automatically.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            // A shorter fade than the 100 ms default: a fast scroll starts dozens of
            // fades at once, and 80 ms is indistinguishable at that speed.
            .crossfade(80)
            .build()
}

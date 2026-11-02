package com.arcmusic

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.arcmusic.data.SettingsRepository
import com.arcmusic.downloader.AutoTag
import com.arcmusic.downloader.YtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ArcMusicApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // A crash log on disk is the only way a report from another phone can be
        // diagnosed properly; it stays local until the user saves it.
        CrashLog.install(this)
        // The player is created on the first play, not here: opening the app to browse
        // the library should not allocate an audio pipeline and its threads.
        // Picks up any Auto(rc) work a previous session could not finish.
        AutoTag.init(this)
        // The launcher alias has to match the saved accent, including after an update.
        runCatching { AppIconManager.apply(this, SettingsRepository.get(this).theme.value) }

        // Unpacking the Python runtime is the slowest part of the first search. Start it
        // while the user is still looking at the library, on IO and after the first
        // frames, so the first search feels immediate instead of frozen.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            delay(1_500)
            runCatching { YtDlpEngine.ensureReadyAsync(this@ArcMusicApp) }
        }
    }

    /**
     * Coil 3 keeps networking in a separate artifact, so the fetcher has to be
     * registered explicitly. Thumbnails then cache to disk automatically.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
}

package com.arcmusic

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.arcmusic.data.AppNightMode
import com.arcmusic.downloader.AutoTag
import com.arcmusic.player.PlayerScreen
import com.arcmusic.player.PlaybackController
import com.arcmusic.ui.MainViewModel
import com.arcmusic.ui.theme.ArcMusicTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val mediaPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // A grant is what makes songs copied in from elsewhere visible, so import
            // straight away rather than waiting for the next app start.
            if (granted.values.any { it }) viewModel.syncLibrary(force = true)
        }

    /** The system's confirmation before this app deletes a file it did not create. */
    private val deleteRequest =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.onDeleteApproved()
            } else {
                viewModel.onDeleteDeclined()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleSharedText(intent)

        setContent {
            val theme by viewModel.theme.collectAsStateWithLifecycle()
            val glass by viewModel.glassMode.collectAsStateWithLifecycle()
            val accent by viewModel.customAccent.collectAsStateWithLifecycle()
            val backgroundHue by viewModel.customBackground.collectAsStateWithLifecycle()
            val surfaceHue by viewModel.customSurface.collectAsStateWithLifecycle()
            val nightMode by viewModel.nightMode.collectAsStateWithLifecycle()
            val dark = when (nightMode) {
                AppNightMode.LIGHT -> false
                AppNightMode.DARK -> true
                AppNightMode.SYSTEM -> isSystemInDarkTheme()
            }
            // The system bar icons have to follow the app's own choice, not the phone's.
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                        )
                    },
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                        )
                    },
                )
            }
            // Some phones allow a 2x text scale, which breaks fixed-height rows and
            // turns every heading into three lines. Cap it at the largest size Android's
            // own slider offers; the app still honours the user's preference below that.
            val density = LocalDensity.current
            val capped = Density(density.density, density.fontScale.coerceAtMost(MAX_FONT_SCALE))
            ArcMusicTheme(
                theme = theme,
                darkTheme = dark,
                glass = glass,
                customAccent = accent,
                customBackground = backgroundHue,
                customSurface = surfaceHue,
            ) {
                CompositionLocalProvider(LocalDensity provides capped) {
                    PlayerScreen(
                        viewModel = viewModel,
                        onRequestPermissions = ::requestPermissions,
                    )
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.deleteRequests.collect { prompt ->
                    deleteRequest.launch(IntentSenderRequest.Builder(prompt.sender).build())
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The player stops publishing position while no UI is visible.
        PlaybackController.setUiVisible(true)
        // Auto(rc) keeps going in the background, just at a gentler pace.
        AutoTag.setUiVisible(true)
        // Picks up songs copied into the download folder by another app.
        viewModel.syncLibrary()
    }

    override fun onStop() {
        PlaybackController.setUiVisible(false)
        AutoTag.setUiVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedText(intent)
    }

    /** Picks a link out of a "share to" payload from the YouTube app or a browser. */
    private fun handleSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val link = URL_PATTERN.find(text)?.value ?: text
        viewModel.onSearchQueryChange(link.trim())
        viewModel.requestSearchFocus()
        viewModel.submitSearch()
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!hasPermission(Manifest.permission.READ_MEDIA_AUDIO)) {
                mediaPermission.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO))
            }
        } else if (!hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            mediaPermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val URL_PATTERN = Regex("""https?://\S+""")

        /** Roughly "Largest" on stock Android. */
        const val MAX_FONT_SCALE = 1.3f
    }
}

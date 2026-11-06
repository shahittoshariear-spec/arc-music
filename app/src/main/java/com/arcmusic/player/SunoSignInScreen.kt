package com.arcmusic.player

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.arcmusic.R
import com.arcmusic.downloader.SunoClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sign-in for Suno.
 *
 * Suno has no API key flow, so the app opens the real page and keeps the resulting
 * cookies. They are captured on their own, separately from the YouTube cookie jar, so
 * signing in here never touches the YouTube session.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SunoSignInScreen(
    onDismiss: () -> Unit,
    onSignedIn: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var finishing by remember { mutableStateOf(false) }
    val holder = remember { mutableStateOf<WebView?>(null) }

    fun finish() {
        if (finishing) return
        val cookie = CookieManager.getInstance().getCookie("https://suno.com").orEmpty()
        if (cookie.isBlank()) {
            onDismiss()
            return
        }
        finishing = true
        SunoClient.saveSession(context, cookie)
        CookieManager.getInstance().flush()
        onSignedIn()
        scope.launch {
            delay(600)
            onDismiss()
        }
    }

    BackHandler { onDismiss() }

    DisposableEffect(Unit) {
        onDispose {
            holder.value?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            holder.value = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.suno_sign_in_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { finish() }) {
                        Text(stringResource(R.string.suno_done))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        AndroidView(
            factory = { viewContext ->
                WebView(viewContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            val cookie = CookieManager.getInstance()
                                .getCookie("https://suno.com")
                            if (SunoClient.looksSignedIn(cookie)) finish()
                        }
                    }
                    loadUrl("https://suno.com/signin")
                    holder.value = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

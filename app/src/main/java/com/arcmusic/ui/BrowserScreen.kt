package com.arcmusic.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.arcmusic.R
import com.arcmusic.downloader.CookieStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one place the app shows a web page: Google's sign-in form.
 *
 * Google will not hand a YouTube session to a third-party login flow, so the account
 * form has to be a real browser session. Everything after that — liked videos, Watch
 * Later, playlists and search — is rendered natively from yt-dlp, never on the web.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onDismiss: () -> Unit,
    onSessionChanged: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var signedIn by remember { mutableStateOf(hasSessionCookie()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    val holder = remember { mutableStateOf<WebView?>(null) }

    fun finishSignIn() {
        if (dismissing) return
        dismissing = true
        CookieStore.save(context)
        CookieManager.getInstance().flush()
        onSessionChanged()
        onMessage(context.getString(R.string.player_sign_in_done))
        scope.launch {
            delay(1200)
            onDismiss()
        }
    }

    fun noticeSession() {
        if (signedIn) return
        if (!hasSessionCookie()) return
        signedIn = true
        finishSignIn()
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
                title = { Text(stringResource(R.string.player_sign_in_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                },
                actions = {
                    if (signedIn) {
                        TextButton(
                            onClick = {
                                CookieManager.getInstance().removeAllCookies(null)
                                CookieManager.getInstance().flush()
                                CookieStore.clear(context)
                                signedIn = false
                                onSessionChanged()
                                onMessage(context.getString(R.string.browse_sign_out_done))
                            }
                        ) {
                            Text(stringResource(R.string.player_sign_out))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = when {
                            signedIn -> stringResource(R.string.player_sign_in_done)
                            failed -> stringResource(R.string.browse_offline)
                            else -> stringResource(R.string.browse_hint)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!signedIn) {
                        Button(
                            onClick = { holder.value?.loadUrl(SIGN_IN_URL) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_account),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.browse_sign_in))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true

                        // Third-party cookies carry the Google account hop, which is
                        // the part that makes the session stick.
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?,
                            ) {
                                loading = true
                                failed = false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                noticeSession()
                            }

                            // Google's flow moves between pages without a full reload.
                            override fun doUpdateVisitedHistory(
                                view: WebView?,
                                url: String?,
                                isReload: Boolean,
                            ) {
                                noticeSession()
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    failed = true
                                }
                            }
                        }

                        loadUrl(if (hasSessionCookie()) START_URL else SIGN_IN_URL)
                        holder.value = this
                    }
                },
            )
        }
    }
}

private const val START_URL = "https://m.youtube.com/"

private const val SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube" +
        "&continue=https%3A%2F%2Fm.youtube.com%2F"

private fun hasSessionCookie(): Boolean {
    val raw = runCatching { CookieManager.getInstance().getCookie("https://www.youtube.com") }
        .getOrNull().orEmpty()
    return raw.contains("LOGIN_INFO") || raw.contains("SAPISID") || raw.contains("__Secure-1PSID")
}

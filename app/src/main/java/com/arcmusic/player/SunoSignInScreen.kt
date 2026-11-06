package com.arcmusic.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.arcmusic.R
import com.arcmusic.downloader.SunoClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sign-in for Suno.
 *
 * Suno has no API key flow, so the app opens the real page and keeps the resulting
 * cookies. Its login buttons open a **popup window** for Google/Apple auth, and a plain
 * WebView has no window of its own to put that in — which is why clicking "Log in" used
 * to leave a black screen. The chrome client below hosts those popups over the page.
 *
 * If a provider still refuses to complete inside a WebView, the paste dialog accepts a
 * cookie header copied from a normal browser; that is the escape hatch for an API with
 * no official token flow.
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
    var failed by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }

    val holder = remember { mutableStateOf<FrameLayout?>(null) }
    val mainView = remember { mutableStateOf<WebView?>(null) }
    val popups = remember { mutableListOf<WebView>() }

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
            runCatching { mainView.value?.destroy() }
            popups.forEach { runCatching { it.destroy() } }
            popups.clear()
            mainView.value = null
            holder.value = null
        }
    }

    val client = remember {
        object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                loading = false
                failed = false
                val cookie = CookieManager.getInstance().getCookie("https://suno.com")
                if (SunoClient.looksSignedIn(cookie)) finish()
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
    }

    fun newWebView(viewContext: Context, allowPopups: Boolean): WebView =
        WebView(viewContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            settings.mediaPlaybackRequiresUserGesture = false
            // A plain Chrome UA: several auth providers reject the "wv" marker.
            settings.userAgentString = USER_AGENT
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = client
            if (allowPopups) {
                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message,
                    ): Boolean {
                        val popup = newWebView(view.context, allowPopups = false)
                        (resultMsg.obj as? WebView.WebViewTransport)?.webView = popup
                        resultMsg.sendToTarget()
                        popups += popup
                        holder.value?.addView(
                            popup,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        return true
                    }

                    override fun onCloseWindow(window: WebView) {
                        holder.value?.removeView(window)
                        popups.remove(window)
                        runCatching { window.destroy() }
                    }
                }
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
                    TextButton(onClick = { showPaste = true }) {
                        Text(stringResource(R.string.suno_paste))
                    }
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
                FrameLayout(viewContext).apply {
                    val web = newWebView(viewContext, allowPopups = true)
                    addView(
                        web,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    web.loadUrl("https://suno.com/signin")
                    holder.value = this
                    mainView.value = web
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (failed) {
            Text(
                text = stringResource(R.string.suno_web_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
            )
        }
    }

    if (showPaste) {
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text(stringResource(R.string.suno_paste_title)) },
            text = {
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = { Text(stringResource(R.string.suno_cookie)) },
                    supportingText = { Text(stringResource(R.string.suno_paste_help)) },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pasted.isNotBlank()) {
                            SunoClient.saveSession(context, pasted)
                            showPaste = false
                            onSignedIn()
                            onDismiss()
                        }
                    },
                ) {
                    Text(stringResource(R.string.suno_paste_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPaste = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

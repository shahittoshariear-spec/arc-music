package com.arcmusic.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** The newest release as GitHub describes it. */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val url: String,
    val sizeBytes: Long,
    val digest: String?,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val localVersion: String) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class NeedsPermission(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * Self-update from the project's GitHub releases.
 *
 * Deliberately manual: the app only touches the network when the user taps check, which
 * keeps the unauthenticated GitHub rate limit (60/hour) and the battery untouched.
 * The downloaded file is checked against the asset digest GitHub publishes, and its
 * package name and version are read back before the installer is ever started.
 */
object AppUpdater {

    private const val API =
        "https://api.github.com/repos/shahittoshariear-spec/arc-music/releases/latest"
    private const val AUTHORITY_SUFFIX = ".updates"
    private const val USER_AGENT = "A(rc)-Updater"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null

    fun check(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        _state.value = UpdateState.Checking
        job = scope.launch {
            runCatching {
                val local = localVersion(app)
                val release = JSONObject(fetch(API))
                val tag = release.optString("tag_name").trim().removePrefix("v")
                if (tag.isBlank()) error("The latest release has no version tag")
                if (!isNewer(tag, local)) {
                    _state.value = UpdateState.UpToDate(local)
                    return@launch
                }
                val asset = pickApk(release.optJSONArray("assets"))
                    ?: error("The latest release has no APK attached")
                UpdateInfo(
                    version = tag,
                    notes = plainNotes(release.optString("body")),
                    url = asset.optString("browser_download_url"),
                    sizeBytes = asset.optLong("size"),
                    digest = asset.optString("digest").takeIf { it.startsWith("sha256:") },
                )
            }.onSuccess { _state.value = UpdateState.Available(it) }
                .onFailure {
                    _state.value = UpdateState.Failed(it.message ?: "Could not reach GitHub")
                }
        }
    }

    fun download(context: Context) {
        val info = (_state.value as? UpdateState.Available)?.info ?: return
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            val directory = File(app.cacheDir, "updates").apply { mkdirs() }
            // One update file at a time; an older download is never going to be used.
            directory.listFiles()?.forEach { it.delete() }
            val target = File(directory, "a-rc-${info.version}.apk")
            runCatching {
                _state.value = UpdateState.Downloading(info, 0f)
                val connection = open(info.url)
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                var read = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            read += count
                            if (total > 0) {
                                _state.value = UpdateState.Downloading(
                                    info,
                                    (read.toFloat() / total).coerceIn(0f, 1f),
                                )
                            }
                        }
                    }
                }
                connection.disconnect()
                if (total > 0 && read != total) error("The download ended early")
                verifyDigest(target, info.digest)
                verifyPackage(app, target)
            }.onSuccess {
                _state.value = UpdateState.Ready(info, target)
            }.onFailure {
                target.delete()
                _state.value = UpdateState.Failed(it.message ?: "Download failed")
            }
        }
    }

    /** Starts the system installer, asking for the unknown-sources grant first if needed. */
    fun install(context: Context) {
        val current = _state.value
        val info = when (current) {
            is UpdateState.Ready -> current.info
            is UpdateState.NeedsPermission -> current.info
            else -> return
        }
        val file = when (current) {
            is UpdateState.Ready -> current.file
            is UpdateState.NeedsPermission -> current.file
            else -> return
        }
        if (!file.isFile || file.length() == 0L) {
            _state.value = UpdateState.Failed("The downloaded file is gone; download it again")
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(info, file)
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + AUTHORITY_SUFFIX,
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            _state.value = UpdateState.Failed(
                "Android would not open the installer: ${it.message.orEmpty()}"
            )
        }
    }

    /** Sends the user to the per-app "install unknown apps" screen. */
    fun requestInstallPermission(context: Context) {
        runCatching {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.packageName),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    // ---- Plumbing ----------------------------------------------------------

    private fun fetch(url: String): String {
        val connection = open(url)
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("GitHub returned HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    private fun pickApk(assets: JSONArray?): JSONObject? {
        if (assets == null) return null
        var best: JSONObject? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (best == null || name.contains("universal", ignoreCase = true)) best = asset
        }
        return best
    }

    private fun localVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "0" }

    private fun isNewer(remote: String, local: String): Boolean {
        val left = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val right = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(i) { 0 }
            val b = right.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun verifyDigest(file: File, digest: String?) {
        if (digest == null) return
        val expected = digest.removePrefix("sha256:").lowercase()
        if (expected.isBlank()) return
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                md.update(buffer, 0, count)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            error("The download did not match the published checksum")
        }
    }

    private fun verifyPackage(context: Context, file: File) {
        val manager = context.applicationContext.packageManager
        val archive = manager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: error("Android could not read the downloaded APK")
        if (archive.packageName != context.packageName) {
            error("That APK belongs to another app")
        }
        val installed = manager.getPackageInfo(context.packageName, 0)
        if (archive.longVersionCode <= installed.longVersionCode) {
            error("The downloaded APK is not newer than the installed one")
        }
    }

    /** Strips the Markdown the release notes are written in, for a small card. */
    private fun plainNotes(raw: String): String = raw.lineSequence()
        .map { line ->
            line.trim()
                .removePrefix("### ").removePrefix("## ").removePrefix("# ")
                .removePrefix("- ").replace("**", "")
        }
        .filterNot { it.startsWith("The APK below") }
        .filter { it.isNotBlank() }
        .take(12)
        .joinToString("\n")
}

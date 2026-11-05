package com.arcmusic.downloader

import android.content.IntentSender
import android.app.RecoverableSecurityException
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.arcmusic.data.DownloadRecord
import com.arcmusic.data.HistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.StandardArtwork
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** What the editor can tell the user after a save attempt. */
sealed interface TagResult {
    data object Saved : TagResult
    data object Failed : TagResult
    /** The file belongs to another app; Android must ask the user before it can be rewritten. */
    data class NeedsPermission(val sender: IntentSender) : TagResult
}

/** The tags the editor can write. A blank field is left exactly as the file has it. */
data class TagEdits(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val year: String,
    val track: String,
)

/**
 * Rewrites the tags of an already-published track with the bundled FFmpeg.
 *
 * The `ffmpeg` module of youtubedl-android only unpacks the binary and its shared
 * libraries; at this version it exposes no execution API at all. The binary is
 * therefore run directly, the same way yt-dlp runs it for a conversion:
 * `<nativeLibraryDir>/libffmpeg.so` with the unpacked `usr/lib` on the loader path.
 *
 * FFmpeg cannot open a `content://` URI, so the bytes are round-tripped through the
 * cache. The result goes back into the *same* MediaStore row rather than a new one —
 * a fresh row would leave the library holding two copies of every song.
 */
object Tagger {

    /** Short reason for the last failure, shown in the editor's message. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Containers that can carry cover art. WebM and WAV have nowhere to put a picture,
     * and AAC is raw ADTS, which cannot even hold a title.
     */
    private val artContainers = setOf("mp3", "flac", "m4a", "mp4", "ogg", "oga", "opus")

    private const val FFMPEG_BINARY = "libffmpeg.so"

    /**
     * Mirrors the paths [com.yausername.ffmpeg.FFmpeg.init] unpacks into and
     * [com.yausername.youtubedl_android.YoutubeDL] puts on `LD_LIBRARY_PATH`.
     */
    private const val UNPACKED_ROOT = "youtubedl-android/packages/ffmpeg/usr/lib"

    /** A conversion takes seconds; anything past this is a hung binary. */
    private const val FFMPEG_TIMEOUT_SECONDS = 180L

    /**
     * Containers the pure-Java tagger can rewrite. WebM and Opus fall through to FFmpeg.
     */
    private val nativeContainers = setOf("mp3", "flac", "m4a", "mp4")

    /** Rewrites tags (and optionally cover art) in place. */
    suspend fun apply(
        context: Context,
        record: DownloadRecord,
        edits: TagEdits,
        cover: File?,
    ): TagResult = withContext(Dispatchers.IO) {
        lastError = null
        val appContext = context.applicationContext

        // Without a container there is no way to pick an encoder.
        val declared = record.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (declared.isEmpty()) return@withContext fail("container")

        val stamp = System.currentTimeMillis()
        val source = File(appContext.cacheDir, "tag_in_$stamp.$declared")
        var tagged: File? = null

        // The cover is the caller's file, not ours: a failed save has to leave that
        // choice intact so the user can try again.
        try {
            val copied = runCatching {
                val stream = if (record.uri.startsWith("/")) {
                    File(record.uri).inputStream()
                } else {
                    appContext.contentResolver.openInputStream(Uri.parse(record.uri))
                }
                stream?.use { input ->
                    source.outputStream().use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
            if (!copied) return@withContext fail("read")

            // Suno and hand-renamed files can carry an extension that does not match the
            // bytes. FFmpeg picks its muxer from the *name*, so a WAV called .mp3 made
            // both the tagger and the converter fail. The real container decides.
            val extension = sniffExtension(source)
            val output = File(appContext.cacheDir, "tag_out_$stamp.$extension")
            tagged = output

            // The in-process tagger first: it needs no executable and covers the
            // formats this app produces most of the time.
            val rewritten = if (writeTags(source, edits, cover, extension)) {
                source
            } else {
                // FFmpeg is still the only path for WebM/Opus and for odd containers.
                if (runCatching { YtDlpEngine.ensureReady(appContext) }.isFailure) {
                    return@withContext fail("engine")
                }
                val exit = runFfmpeg(
                    appContext,
                    arguments(source, output, cover, edits, extension),
                ) ?: return@withContext fail("ffmpeg")
                if (exit != 0 || output.length() == 0L) return@withContext fail("convert")
                output
            }

            when (val written = writeBack(appContext, record.uri, rewritten)) {
                WriteResult.Saved -> Unit
                is WriteResult.Permission -> return@withContext TagResult.NeedsPermission(
                    written.sender
                )
                WriteResult.Failed -> return@withContext fail("write")
            }

            val latest = HistoryRepository.get(appContext).items.value
                .firstOrNull { it.id == record.id } ?: record
            HistoryRepository.get(appContext).update(
                latest.copy(
                    // A blank field is not written to the file, so the list has to keep
                    // saying whatever the file still says.
                    title = edits.title.trim().ifBlank { latest.title },
                    uploader = edits.artist.trim().ifBlank { latest.uploader },
                    album = edits.album.trim().ifBlank { latest.album },
                    albumArtist = edits.albumArtist.trim().ifBlank { latest.albumArtist },
                    genre = edits.genre.trim().ifBlank { latest.genre },
                    year = edits.year.trim().ifBlank { latest.year },
                    trackNumber = edits.track.trim().ifBlank { latest.trackNumber },
                    metadataUpdatedAt = System.currentTimeMillis(),
                    // Cover art and the tag block change the byte count.
                    sizeBytes = rewritten.length(),
                )
            )
            TagResult.Saved
        } finally {
            source.delete()
            tagged?.delete()
        }
    }

    private fun fail(reason: String): TagResult {
        lastError = reason
        return TagResult.Failed
    }

    private sealed interface WriteResult {
        data object Saved : WriteResult
        data object Failed : WriteResult
        data class Permission(val sender: IntentSender) : WriteResult
    }

    /**
     * Replaces the bytes behind a MediaStore row. `"wt"` is the documented truncating
     * mode, but some providers reject it; the descriptor path is the fallback.
     *
     * A file the app did not create cannot be rewritten without the user's consent on
     * Android 11+. That consent is an [IntentSender] the editor has to launch, which is
     * why this returns more than a boolean: silently reporting "could not save" was what
     * made imported songs impossible to edit.
     */
    private fun writeBack(context: Context, uri: String, file: File): WriteResult {
        val target = runCatching { Uri.parse(uri) }.getOrNull() ?: return WriteResult.Failed

        // Files moved into the folder by hand are indexed with a file:// URI, and the
        // content resolver's write modes do not reliably accept those. Writing the file
        // directly is the only path that works for them.
        if (target.scheme == "file" || uri.startsWith("/")) {
            val path = if (uri.startsWith("/")) uri else target.path ?: return WriteResult.Failed
            val written = runCatching {
                File(path).outputStream().use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
                true
            }.getOrDefault(false)
            return if (written) WriteResult.Saved else WriteResult.Failed
        }

        for (mode in listOf("wt", "w")) {
            try {
                val output = context.contentResolver.openOutputStream(target, mode)
                if (output != null) {
                    output.use { stream ->
                        file.inputStream().use { input -> input.copyTo(stream) }
                    }
                    return WriteResult.Saved
                }
            } catch (denied: SecurityException) {
                permissionFor(context, target, denied)?.let { return WriteResult.Permission(it) }
                return WriteResult.Failed
            } catch (_: Exception) {
                // Try the next mode.
            }
        }

        try {
            context.contentResolver.openFileDescriptor(target, "rwt")?.use { descriptor ->
                java.io.FileOutputStream(descriptor.fileDescriptor).use { output ->
                    output.channel.truncate(0L)
                    file.inputStream().use { input -> input.copyTo(output) }
                }
                return WriteResult.Saved
            }
        } catch (denied: SecurityException) {
            permissionFor(context, target, denied)?.let { return WriteResult.Permission(it) }
            return WriteResult.Failed
        } catch (_: Exception) {
            // Fall through to the read-write descriptor.
        }

        val rwWritten = runCatching {
            context.contentResolver.openFileDescriptor(target, "rw")?.use { descriptor ->
                java.io.FileOutputStream(descriptor.fileDescriptor).use { output ->
                    output.channel.truncate(0L)
                    file.inputStream().use { input -> input.copyTo(output) }
                }
                true
            } ?: false
        }.getOrDefault(false)
        return if (rwWritten) WriteResult.Saved else WriteResult.Failed
    }

    /** The system dialog that grants write access to another app's media row. */
    private fun permissionFor(
        context: Context,
        uri: Uri,
        error: SecurityException,
    ): IntentSender? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
                    .intentSender
            }.getOrNull()?.let { return it }
        }
        // Android 10 wraps the request in the exception itself.
        var cause: Throwable? = error
        while (cause != null) {
            (cause as? RecoverableSecurityException)?.let {
                return it.userAction.actionIntent.intentSender
            }
            cause = cause.cause
        }
        return null
    }

    /**
     * Reads the container from the bytes, not the file name. A wrong extension breaks
     * both taggers, and a Suno export named `.mp3` can be anything.
     */
    private fun sniffExtension(file: File): String {
        val header = ByteArray(12)
        val read = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(0)
        if (read >= 12) {
            fun at(index: Int): Int = header[index].toInt() and 0xFF
            when {
                at(0) == 0x66 && at(1) == 0x4C && at(2) == 0x61 && at(3) == 0x43 -> return "flac"
                at(0) == 0x4F && at(1) == 0x67 && at(2) == 0x67 && at(3) == 0x53 -> return "ogg"
                at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 -> return "wav"
                at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70 -> return "m4a"
                at(0) == 0x49 && at(1) == 0x44 && at(2) == 0x33 -> return "mp3"
                at(0) == 0xFF && (at(1) and 0xE0) == 0xE0 -> return "mp3"
            }
        }
        return file.extension.lowercase(Locale.ROOT)
    }

    /**
     * Writes tags and artwork in-process. A blank field is skipped, matching FFmpeg's
     * behaviour: the user can change one field without having to retype the rest.
     */
    private fun writeTags(file: File, edits: TagEdits, cover: File?, extension: String): Boolean {
        if (extension.lowercase(Locale.ROOT) !in nativeContainers) return false

        /** One attempt; [clearOld] drops whatever the file already carried. */
        fun attempt(clearOld: Boolean): Boolean = runCatching {
            val audio = AudioFileIO.read(file)
            if (clearOld) runCatching { audio.setTag(null) }
            val tag = audio.tagOrCreateAndSetDefault

            fun put(key: FieldKey, value: String) {
                val text = value.trim()
                if (text.isNotEmpty()) tag.setField(key, text)
            }

            put(FieldKey.TITLE, edits.title)
            put(FieldKey.ARTIST, edits.artist)
            put(FieldKey.ALBUM, edits.album)
            put(FieldKey.ALBUM_ARTIST, edits.albumArtist)
            put(FieldKey.GENRE, edits.genre)
            put(FieldKey.YEAR, edits.year)
            put(FieldKey.TRACK, edits.track)

            if (cover != null) {
                val bytes = cover.readBytes()
                if (bytes.isNotEmpty()) {
                    val artwork = StandardArtwork()
                    artwork.binaryData = bytes
                    artwork.mimeType = mimeTypeOf(bytes)
                    artwork.description = "Cover"
                    artwork.pictureType = PictureTypes.DEFAULT_ID
                    artwork.isLinked = false
                    tag.setField(artwork)
                }
            }

            audio.commit()
            true
        }.getOrDefault(false)

        // Some encoders — Suno's MP3s among them — write ID3 frames that jaudiotagger
        // cannot map, and the commit fails without saying which field. A second pass
        // that throws the old tag away is what lets those files be edited at all.
        return attempt(clearOld = false) || attempt(clearOld = true)
    }

    private fun mimeTypeOf(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return "image/jpeg"
        }
        if (bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) {
            return "image/png"
        }
        return "image/jpeg"
    }

    /** Builds the FFmpeg argument list: copy the streams, replace only the given tags. */
    private fun arguments(
        source: File,
        tagged: File,
        cover: File?,
        edits: TagEdits,
        extension: String,
    ): List<String> {
        val artCapable = extension in artContainers
        val coverFile = if (artCapable) cover else null
        // With no new art, a container that can hold a picture keeps the one the file
        // already has: `-map 0:a` on its own would strip it.
        val keepArt = artCapable && coverFile == null

        val args = mutableListOf("-y", "-i", source.absolutePath)
        if (coverFile != null) args += listOf("-i", coverFile.absolutePath)
        args += listOf("-map", "0:a")
        if (coverFile != null) {
            args += listOf("-map", "1")
        } else if (keepArt) {
            // The `?` makes the map optional, so a file with no art still converts.
            args += listOf("-map", "0:v?")
        }
        args += listOf("-c:a", "copy")
        if (keepArt) {
            // Copied rather than re-encoded: what came out of the file is already a
            // valid picture. The disposition has to be restated here, because the mp3
            // and flac muxers only write art for a stream marked as a picture.
            args += listOf("-c:v", "copy", "-disposition:v", "attached_pic")
        }

        val tags = linkedMapOf(
            "title" to edits.title,
            "artist" to edits.artist,
            "album" to edits.album,
            "album_artist" to edits.albumArtist,
            "genre" to edits.genre,
            // Players read the year out of the standard `date` tag.
            "date" to edits.year,
            "track" to edits.track,
        )
        tags.forEach { (key, value) ->
            val text = value.trim()
            // Leaving a field out keeps whatever the file already has; writing an
            // empty tag would wipe it.
            if (text.isNotEmpty()) args += listOf("-metadata", "$key=$text")
        }

        if (coverFile != null) {
            // A picked image can be a PNG, WebP or HEIC, none of which is a valid APIC
            // payload; mjpeg is the one picture format every player understands.
            args += listOf(
                "-c:v", "mjpeg",
                "-pix_fmt", "yuvj420p",
                "-vf", "scale=w=1000:h=1000:force_original_aspect_ratio=decrease",
                "-metadata:s:v", "title=Album cover",
                "-metadata:s:v", "comment=Cover (front)",
                "-disposition:v", "attached_pic",
            )
        }

        // An MP3 muxer option: any other muxer fails the whole run if it is passed.
        if (extension == "mp3") args += listOf("-id3v2_version", "3")

        args += tagged.absolutePath
        return args
    }

    /** Returns FFmpeg's exit code, or null when it could not be started at all. */
    private fun runFfmpeg(appContext: Context, args: List<String>): Int? {
        val binary = File(appContext.applicationInfo.nativeLibraryDir, FFMPEG_BINARY)
        val libraries = File(appContext.noBackupFilesDir, UNPACKED_ROOT)
        if (!binary.isFile || !libraries.isDirectory) return null

        return runCatching {
            val process = ProcessBuilder(listOf(binary.absolutePath) + args)
                .redirectErrorStream(true)
                .apply {
                    environment().apply {
                        this["LD_LIBRARY_PATH"] = libraries.absolutePath
                        this["TMPDIR"] = appContext.cacheDir.absolutePath
                        this["HOME"] = appContext.cacheDir.absolutePath
                        this["PATH"] =
                            System.getenv("PATH").orEmpty() + ":" +
                                appContext.applicationInfo.nativeLibraryDir
                    }
                }
                .start()

            // FFmpeg blocks once its output pipe fills up, so the pipe has to be read
            // to the end before waiting on the process. A hung binary blocks that read
            // forever, so a watchdog kills it and lets the read return.
            val watchdog = Thread {
                runCatching {
                    if (!process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                    }
                }
            }.apply { isDaemon = true }
            watchdog.start()

            try {
                val buffer = ByteArray(8 * 1024)
                process.inputStream.use { stream ->
                    while (stream.read(buffer) >= 0) {
                        // Nothing in the output is worth keeping; only the exit code is.
                    }
                }
                process.waitFor()
            } finally {
                runCatching { process.destroyForcibly() }
                watchdog.interrupt()
            }
        }.getOrNull()
    }
}

package com.arcmusic.downloader

import android.content.Context
import android.net.Uri
import com.arcmusic.data.DownloadRecord
import com.arcmusic.data.HistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

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

    /** Rewrites tags (and optionally cover art) in place. Returns false on failure. */
    suspend fun apply(
        context: Context,
        record: DownloadRecord,
        edits: TagEdits,
        cover: File?,
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext

        // Without a container there is no way to tell FFmpeg which muxer to use.
        val extension = record.fileName.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return@withContext false

        // Unpacks the Python and FFmpeg payload on first use, and can fail on a full disk.
        if (runCatching { YtDlpEngine.ensureReady(appContext) }.isFailure) {
            return@withContext false
        }

        val stamp = System.currentTimeMillis()
        val source = File(appContext.cacheDir, "tag_in_$stamp.$extension")
        val tagged = File(appContext.cacheDir, "tag_out_$stamp.$extension")

        // The cover is the caller's file, not ours: a failed save has to leave that
        // choice intact so the user can try again.
        try {
            val copied = runCatching {
                appContext.contentResolver.openInputStream(Uri.parse(record.uri))?.use { input ->
                    source.outputStream().use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
            if (!copied) return@withContext false

            val exit = runFfmpeg(appContext, arguments(source, tagged, cover, edits, extension))
                ?: return@withContext false
            if (exit != 0 || tagged.length() == 0L) return@withContext false

            val written = runCatching {
                appContext.contentResolver.openOutputStream(Uri.parse(record.uri), "wt")
                    ?.use { output ->
                        tagged.inputStream().use { input -> input.copyTo(output) }
                    } != null
            }.getOrDefault(false)
            if (!written) return@withContext false

            HistoryRepository.get(appContext).update(
                record.copy(
                    // A blank field is not written to the file, so the list has to keep
                    // saying whatever the file still says.
                    title = edits.title.trim().ifBlank { record.title },
                    uploader = edits.artist.trim().ifBlank { record.uploader },
                    // Cover art and the tag block change the byte count.
                    sizeBytes = tagged.length(),
                )
            )
            true
        } finally {
            source.delete()
            tagged.delete()
        }
    }

    /** Downloads a YouTube thumbnail to a cache file for use as cover art. */
    suspend fun fetchThumbnail(context: Context, url: String): File? =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext null
            val target = File(
                context.applicationContext.cacheDir,
                "cover_thumb_${System.currentTimeMillis()}.jpg",
            )
            // Thumbnails come off i.ytimg.com as JPEG, and the name is only a hint:
            // FFmpeg probes the content either way.
            val copied = runCatching {
                URL(url).openStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.length() > 0
            }.getOrDefault(false)

            if (copied) {
                target
            } else {
                target.delete()
                null
            }
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
            // to the end before waiting on the process.
            val buffer = ByteArray(8 * 1024)
            process.inputStream.use { stream ->
                while (stream.read(buffer) >= 0) {
                    // Nothing in the output is worth keeping; only the exit code is.
                }
            }
            process.waitFor()
        }.getOrNull()
    }
}

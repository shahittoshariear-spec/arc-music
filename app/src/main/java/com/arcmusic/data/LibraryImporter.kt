package com.arcmusic.data

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import com.arcmusic.downloader.MediaPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * Adopts audio that arrived in the download folder from somewhere else: copied over by a
 * file manager or a computer, or left there by another app.
 *
 * The library is a list of records written when a download finishes, so a file moved in
 * afterwards had no way to appear. This walks the folder and writes a record for every
 * file the library does not already know, taking its title, artist and album from the
 * media database and its cover from the embedded artwork.
 */
object LibraryImporter {

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "opus", "ogg", "oga", "webm", "wav", "aac",
    )

    /** An automatic pass skips the folder if one ran this recently. */
    private const val MIN_INTERVAL_MS = 60_000L

    @Volatile
    private var lastRunAt = 0L

    /** Cover extraction runs after the records exist, so the list fills in visibly. */
    private val artScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var artJob: Job? = null

    /** True once the user has let the app see audio files it did not create itself. */
    fun canRead(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Imports everything new and returns how many songs were added. Cheap to call: it
     * only touches the disk when the app has the permission and the throttle allows it.
     */
    suspend fun sync(context: Context, force: Boolean = false): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        if (!force && now - lastRunAt < MIN_INTERVAL_MS) return@withContext 0
        if (!canRead(app)) return@withContext 0
        lastRunAt = now

        val folder = SettingsRepository.get(app).folder.value
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            folder,
        )

        val history = HistoryRepository.get(app)
        // A library written by an earlier build can hold one file twice, because a
        // download stores its row as content://media/external_primary/... while a scan
        // builds content://media/external/.... Both point at the same row.
        dropDuplicateRecords(history)
        val known = history.items.value.mapTo(HashSet()) { mediaKey(it.uri) }
        val knownFiles = history.items.value
            .mapTo(HashSet()) { fileKey(it.fileName, it.sizeBytes) }
        val prefix = "Music/$folder"
        val indexed = indexedAudio(app, prefix)
        val added = mutableListOf<DownloadRecord>()

        // Songs saved before their duration was recorded show no time in the list, while
        // later ones do. The media database already knows every one of them, so fill the
        // gaps in the same pass instead of leaving rows that look different.
        if (indexed.isNotEmpty()) {
            val durations = indexed.values
                .associate { mediaKey(it.uri.toString()) to it.durationMs }
            val missing = history.items.value
                .filter { it.durationSeconds <= 0L }
                .mapNotNull { record ->
                    val millis = durations[mediaKey(record.uri)] ?: return@mapNotNull null
                    if (millis <= 0L) null else record.copy(durationSeconds = millis / 1000L)
                }
            if (missing.isNotEmpty()) history.updateAll(missing)
        }

        // Anything the media database already knows about, which is the normal case for
        // files a file manager moved in. Using its uri keeps playback and deletion on
        // the same footing as a song this app downloaded itself.
        indexed.forEach { (key, entry) ->
            if (!known.add(mediaKey(entry.uri.toString()))) return@forEach
            val file = File(root, key.removePrefix("$prefix/"))
            if (!knownFiles.add(fileKey(file.name, file.length()))) return@forEach
            added += record(
                uri = entry.uri,
                entry = entry,
                name = file.name,
                sizeBytes = file.length(),
                modifiedAt = file.lastModified(),
            )
        }

        // Then anything sitting on disk that has not been indexed yet, which is common
        // right after a copy over USB. Directory listing can be empty on some builds,
        // so this pass is optional rather than required.
        if (root.isDirectory) {
            root.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
                .forEach { file ->
                    val relative = file.absolutePath
                        .removePrefix(root.absolutePath)
                        .trimStart(File.separatorChar)
                        .replace(File.separatorChar, '/')
                    if (indexed.containsKey("$prefix/$relative")) return@forEach
                    val uri = runCatching {
                        // Indexing gives ExoPlayer a stable content:// uri and lets the
                        // system's delete dialog act on the file later.
                        withTimeoutOrNull(2_000L) { indexPath(app, file) }
                    }.getOrNull() ?: Uri.fromFile(file)
                    // The same file adopted twice yields the same media row, so this
                    // is also what stops a repeated import from adding duplicates.
                    if (!known.add(mediaKey(uri.toString()))) return@forEach
                    if (!knownFiles.add(fileKey(file.name, file.length()))) return@forEach
                    added += record(
                        uri = uri,
                        entry = null,
                        name = file.name,
                        sizeBytes = file.length(),
                        modifiedAt = file.lastModified(),
                    )
                }
        }

        if (added.isNotEmpty()) history.addAll(added)
        // Runs even when nothing new arrived, so a large first import finishes its
        // covers over the following starts instead of all at once.
        fillArt(app, ART_PASS_LIMIT)
        added.size
    }

    private data class Indexed(
        val uri: Uri,
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
        val mimeType: String?,
    )

    /** One media database pass for the whole folder, keyed by path below `Music/`. */
    private fun indexedAudio(context: Context, prefix: String): Map<String, Indexed> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
        )
        val result = HashMap<String, Indexed>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
                arrayOf(escapeLike(prefix) + "%"),
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    val relative = cursor.getString(pathColumn).orEmpty().replace('\\', '/')
                    val id = cursor.getLong(idColumn)
                    result[relative.trimEnd('/') + "/" + name] = Indexed(
                        uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            id,
                        ),
                        title = cursor.getString(titleColumn),
                        artist = cursor.getString(artistColumn),
                        album = cursor.getString(albumColumn),
                        durationMs = cursor.getLong(durationColumn),
                        mimeType = cursor.getString(mimeColumn),
                    )
                }
            }
        }
        return result
    }

    /**
     * Two records for one file are indistinguishable in the list and worse than that:
     * deleting one removes the file, so the other stops playing. A second media row for
     * the same path (which a scanner can create) gives the copies different uris, so the
     * name and byte size are checked as well. The record that knows where it was
     * downloaded from wins, since it can still be fetched again.
     */
    private fun dropDuplicateRecords(history: HistoryRepository) {
        val seenUris = HashSet<String>()
        val seenFiles = HashSet<String>()
        val drop = mutableListOf<String>()
        history.items.value
            .sortedByDescending { it.sourceUrl != null }
            .forEach { record ->
                val uriKey = mediaKey(record.uri)
                val fileKey = fileKey(record.fileName, record.sizeBytes)
                if (!seenUris.add(uriKey) || !seenFiles.add(fileKey)) drop += record.id
            }
        if (drop.isNotEmpty()) history.removeAll(drop)
    }

    /**
     * Identity of a file on disk. Byte size is part of the key so two different songs
     * that happen to share a name are still two songs. A record without a size (only
     * possible for a hand-built entry) gets a key of its own rather than colliding.
     */
    private fun fileKey(name: String, size: Long): String =
        if (size > 0L) name.lowercase() + "|" + size
        else name.lowercase() + "|0|" + zeroSizeKeys.incrementAndGet()

    /**
     * A media row can be addressed through `external` or `external_primary`; both are
     * the same row on Android 10 and later. Comparing the volume and id instead of the
     * raw string is what makes a downloaded song and a scanned song the same song.
     */
    private fun mediaKey(raw: String): String {
        val parsed = runCatching { Uri.parse(raw) }.getOrNull() ?: return raw
        if (parsed.scheme != "content") return raw
        val segments = parsed.pathSegments
        if (segments.size < 4 || segments[1] != "audio" || segments[2] != "media") return raw
        val volume = if (segments[0] == "external_primary") "external" else segments[0]
        return "media:$volume:${segments[3]}"
    }

    private fun record(
        uri: Uri,
        entry: Indexed?,
        name: String,
        sizeBytes: Long,
        modifiedAt: Long,
    ): DownloadRecord {
        val extension = name.substringAfterLast('.', "").lowercase()
        return DownloadRecord(
            id = "local:$uri",
            title = entry?.title?.takeIf { it.isNotBlank() }
                ?: name.substringBeforeLast('.'),
            uploader = entry?.artist?.takeIf { it.isNotBlank() && it != UNKNOWN },
            thumbnailUrl = null,
            formatName = when (extension) {
                "mp3" -> AudioFormat.MP3.name
                "flac" -> AudioFormat.FLAC.name
                else -> AudioFormat.ORIGINAL.name
            },
            uri = uri.toString(),
            fileName = name,
            mimeType = entry?.mimeType?.takeIf { it.isNotBlank() }
                ?: MediaPublisher.mimeTypeFor(extension),
            sizeBytes = sizeBytes,
            durationSeconds = (entry?.durationMs ?: 0L) / 1000L,
            createdAt = modifiedAt,
            album = entry?.album?.takeIf { it.isNotBlank() && it != UNKNOWN },
        )
    }

    /**
     * True when a row has no art, or points at a cover file that is no longer there.
     * The second case is what made an already-tagged song go blank later: the record
     * still pointed at a file that had been cleaned up, so nothing loaded.
     */
    private fun needsArt(record: DownloadRecord, hq: Boolean): Boolean {
        val art = record.thumbnailUrl
        if (art.isNullOrBlank()) return true
        if (!art.startsWith("file://")) return false
        val file = File(Uri.parse(art).path.orEmpty())
        if (!file.isFile || file.length() == 0L) return true
        // A standard-size extraction does not satisfy the HQ setting. Only this app's own
        // scan files are re-made; a cover written by Auto(rc) is already full size.
        return hq && file.name.startsWith("scan_") && !file.name.startsWith("scan_hq_")
    }

    /**
     * The embedded picture is the most reliable source: Auto(rc) and the editor write the
     * cover into the audio file itself, so it survives a lost library cover.
     */
    private fun artworkFor(context: Context, rawUri: String, px: Int): android.graphics.Bitmap? {
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return null
        runCatching {
            context.contentResolver.loadThumbnail(uri, Size(px, px), null)
        }.getOrNull()?.let { return it }

        return runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture?.let { bytes ->
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()
    }

    private fun scaleDown(
        bitmap: android.graphics.Bitmap,
        targetPx: Int,
    ): android.graphics.Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= targetPx) return bitmap
        val scale = targetPx.toFloat() / largest
        return runCatching {
            android.graphics.Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { bitmap.recycle() }
        }.getOrDefault(bitmap)
    }

    /**
     * Pulls the embedded cover out of files that have none, a limited number at a time.
     * Thumbnail extraction is a decode plus a JPEG encode per song, so a first import of
     * a few hundred files is spread over several passes instead of pinning a core for
     * half a minute.
     */
    private fun fillArt(context: Context, limit: Int) {
        if (limit <= 0) return
        artJob?.cancel()
        artJob = artScope.launch {
            val history = HistoryRepository.get(context)
            val directory = File(context.filesDir, "covers").apply { mkdirs() }
            // A library imported from a PC is where the blurry covers show up most:
            // the embedded picture is decoded at the size the setting asks for.
            val targetPx = if (SettingsRepository.get(context).hqArtwork.value) {
                HQ_COVER_PX
            } else {
                COVER_PX
            }
            val batch = mutableListOf<DownloadRecord>()

            fun flush() {
                if (batch.isEmpty()) return
                // Merge into the live rows: a tag edit or an Auto(rc) pass may have
                // updated the record while these covers were being decoded.
                val live = history.items.value.associateBy { it.id }
                val merged = batch.mapNotNull { art ->
                    val current = live[art.id] ?: return@mapNotNull null
                    current.copy(thumbnailUrl = art.thumbnailUrl)
                }
                history.updateAll(merged)
                batch.clear()
            }

            val pending = history.items.value
                .filter { needsArt(it, hq = targetPx > COVER_PX) }
                .take(limit)

            for (record in pending) {
                val bitmap = artworkFor(context, record.uri, targetPx) ?: continue
                val scaled = scaleDown(bitmap, targetPx)
                val prefix = if (targetPx > COVER_PX) "scan_hq_" else "scan_"
                val target = File(directory, "$prefix${record.id.hashCode()}.jpg")
                val saved = runCatching {
                    target.outputStream().use {
                        scaled.compress(Bitmap.CompressFormat.JPEG, 92, it)
                    }
                }.getOrDefault(false)
                if (scaled !== bitmap) runCatching { bitmap.recycle() }
                scaled.recycle()
                if (!saved) continue
                // Drop the smaller scan now that the HQ one exists.
                record.thumbnailUrl
                    ?.takeIf { it.startsWith("file://") }
                    ?.let { Uri.parse(it).path.orEmpty() }
                    ?.let(::File)
                    ?.takeIf { it.name.startsWith("scan_") && it != target }
                    ?.delete()
                batch += record.copy(thumbnailUrl = "file://" + target.absolutePath)
                if (batch.size >= ART_BATCH) flush()
            }
            flush()
        }
    }

    private fun escapeLike(text: String): String = text
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    /** Asks the media scanner for a file's content uri, waiting for its callback. */
    private suspend fun indexPath(context: Context, file: File): Uri? =
        suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(MediaPublisher.mimeTypeFor(file.extension)),
            ) { _, uri ->
                if (continuation.isActive) continuation.resume(uri)
            }
        }

    private const val UNKNOWN = "<unknown>"

    /** Ids of records this importer created, as opposed to a download. */
    private const val IMPORTED_PREFIX = "local:"

    /** Covers are applied in groups so the library is not re-sorted per song. */
    private const val ART_BATCH = 16

    /** Covers extracted per pass, kept small so one import does not pin a core. */
    private const val ART_PASS_LIMIT = 120

    /** Longest edge of a repaired cover; 384 px is plenty for a 54 dp row and a session. */
    private const val COVER_PX = 384
    private const val HQ_COVER_PX = 1024

    private val zeroSizeKeys = java.util.concurrent.atomic.AtomicLong()
}

package com.arcmusic.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.arcmusic.R
import com.arcmusic.data.DownloadRecord
import com.arcmusic.downloader.TagEdits
import com.arcmusic.downloader.Tagger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Label for the confirm button. There is no tag_* string for it yet and strings.xml
 * belongs to someone else, so it is named here: the dialog needs
 * `<string name="tag_save">Save</string>` to build.
 */
private val tagSaveLabel = R.string.tag_save

/** A 64dp preview never needs more than this, and a photo decoded whole would be huge. */
private const val PREVIEW_PX = 256

private val PREVIEW_SIZE = 64.dp

/**
 * Edits the tags of one finished download.
 *
 * The dialog owns the cover image it hands to [Tagger] — the tagger leaves it alone, so
 * a failed save can be retried with the same art.
 */
@Composable
fun TagEditorDialog(
    record: DownloadRecord,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by remember(record.id) { mutableStateOf(record.title) }
    var artist by remember(record.id) { mutableStateOf(record.uploader.orEmpty()) }
    var album by remember(record.id) { mutableStateOf("") }
    var albumArtist by remember(record.id) { mutableStateOf("") }
    var genre by remember(record.id) { mutableStateOf("") }
    var year by remember(record.id) { mutableStateOf("") }
    var track by remember(record.id) { mutableStateOf("") }

    var cover by remember(record.id) { mutableStateOf<File?>(null) }
    var saving by remember { mutableStateOf(false) }
    var artBusy by remember { mutableStateOf(false) }
    val working = saving || artBusy

    // Every cache file this dialog created, dropped when it closes.
    val scratch = remember(record.id) { mutableListOf<File>() }
    DisposableEffect(record.id) {
        onDispose { scratch.forEach { it.delete() } }
    }

    fun adopt(file: File) {
        scratch += file
        cover = file
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                artBusy = true
                val picked = cacheImage(context, uri)
                artBusy = false
                if (picked != null) {
                    adopt(picked)
                } else {
                    onMessage(context.getString(R.string.tag_art_failed))
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.tag_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.tag_title_label)) },
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text(stringResource(R.string.tag_artist)) },
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text(stringResource(R.string.tag_album)) },
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = albumArtist,
                    onValueChange = { albumArtist = it },
                    label = { Text(stringResource(R.string.tag_album_artist)) },
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = genre,
                    onValueChange = { genre = it },
                    label = { Text(stringResource(R.string.tag_genre)) },
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Year and track are short, so they share a row and get a numeric keypad.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = year,
                        onValueChange = { year = it },
                        label = { Text(stringResource(R.string.tag_year)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !working,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = track,
                        onValueChange = { track = it },
                        label = { Text(stringResource(R.string.tag_track)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !working,
                        modifier = Modifier.weight(1f),
                    )
                }

                HorizontalDivider()

                Text(
                    text = stringResource(R.string.tag_cover),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverPreview(cover)
                    if (cover == null) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.tag_cover_none),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Column {
                    TextButton(
                        onClick = {
                            picker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                )
                            )
                        },
                        enabled = !working,
                    ) {
                        Text(stringResource(R.string.tag_cover_pick))
                    }

                    if (record.thumbnailUrl != null) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    artBusy = true
                                    val fetched = Tagger.fetchThumbnail(
                                        context,
                                        record.thumbnailUrl.orEmpty(),
                                    )
                                    artBusy = false
                                    if (fetched != null) {
                                        adopt(fetched)
                                        onMessage(context.getString(R.string.tag_art_done))
                                    } else {
                                        onMessage(context.getString(R.string.tag_art_failed))
                                    }
                                }
                            },
                            enabled = !working,
                        ) {
                            Text(stringResource(R.string.tag_cover_thumb))
                        }
                    }

                    if (cover != null) {
                        TextButton(onClick = { cover = null }, enabled = !working) {
                            Text(stringResource(R.string.tag_cover_clear))
                        }
                    }
                }

                if (working) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        // Only the save has a label for it; fetching art is not a save.
                        if (saving) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.tag_saving),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        saving = true
                        val edits = TagEdits(
                            title = title,
                            artist = artist,
                            album = album,
                            albumArtist = albumArtist,
                            genre = genre,
                            year = year,
                            track = track,
                        )
                        val saved = Tagger.apply(context, record, edits, cover)
                        saving = false
                        onMessage(
                            context.getString(
                                if (saved) R.string.tag_saved else R.string.tag_failed,
                            )
                        )
                        if (saved) onSaved()
                    }
                },
                enabled = !working,
            ) {
                Text(stringResource(tagSaveLabel))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * The chosen cover, or the same placeholder the history rows use when there is none.
 */
@Composable
private fun CoverPreview(cover: File?) {
    val decoded by produceState<ImageBitmap?>(null, cover) {
        value = cover?.let { file -> withContext(Dispatchers.IO) { decodePreview(file) } }
    }

    Box(
        modifier = Modifier
            .size(PREVIEW_SIZE)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = decoded
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The picker hands back a URI that may only be readable inside this process and FFmpeg
 * needs a path, so the image is copied into the cache first. The extension is only a
 * hint for FFmpeg, which probes the content anyway.
 */
private suspend fun cacheImage(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val extension = resolver.getType(uri)
        ?.substringAfterLast('/')
        ?.filter { it.isLetterOrDigit() }
        ?.takeIf { it.isNotEmpty() }
        ?: "jpg"

    val target = File(context.cacheDir, "cover_${System.currentTimeMillis()}.$extension")
    val copied = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } != null
    }.getOrDefault(false)

    if (copied && target.length() > 0) {
        target
    } else {
        target.delete()
        null
    }
}

/** Decodes at a sample size, because the source is a full-size photo. */
private fun decodePreview(file: File): ImageBitmap? {
    if (!file.isFile) return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= PREVIEW_PX &&
        bounds.outHeight / (sample * 2) >= PREVIEW_PX
    ) {
        sample *= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
}

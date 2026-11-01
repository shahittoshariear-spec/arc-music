package com.arcmusic.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.arcmusic.R
import com.arcmusic.data.DownloadRecord
import com.arcmusic.data.HistoryRepository
import com.arcmusic.data.LyricsRepository
import com.arcmusic.downloader.AutoTag
import com.arcmusic.downloader.LyricsClient
import com.arcmusic.downloader.TagEdits
import com.arcmusic.downloader.Tagger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A 256px preview is more than a 124dp tile ever needs. */
private const val PREVIEW_PX = 256

/**
 * Edits the tags of one finished download.
 *
 * Fields start from what the record already knows, so opening this after Auto(rc) shows
 * the metadata it wrote rather than empty boxes. Artwork is chosen by tapping the tile;
 * Auto(rc) offers every catalogue match when more than one is plausible.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var album by remember(record.id) { mutableStateOf(record.album.orEmpty()) }
    var albumArtist by remember(record.id) { mutableStateOf(record.albumArtist.orEmpty()) }
    var genre by remember(record.id) { mutableStateOf(record.genre.orEmpty()) }
    var year by remember(record.id) { mutableStateOf(record.year.orEmpty()) }
    var track by remember(record.id) { mutableStateOf(record.trackNumber.orEmpty()) }

    // The cover Auto(rc) last stored is a local file; a remote thumbnail is only shown
    // until a real cover replaces it.
    var existingArt by remember(record.id) { mutableStateOf(record.thumbnailUrl) }
    var cover by remember(record.id) {
        mutableStateOf(
            record.thumbnailUrl
                ?.takeIf { it.startsWith("file://") }
                ?.let { File(Uri.parse(it).path.orEmpty()) }
                ?.takeIf { it.isFile }
        )
    }
    var coverUrl by remember(record.id) { mutableStateOf<String?>(null) }

    var saving by remember { mutableStateOf(false) }
    var artBusy by remember { mutableStateOf(false) }
    var autoBusy by remember { mutableStateOf(false) }
    var showArt by remember { mutableStateOf(false) }
    var optionsLoading by remember { mutableStateOf(false) }
    var options by remember(record.id) { mutableStateOf<List<AutoTag.CoverChoice>>(emptyList()) }
    var candidates by remember(record.id) { mutableStateOf<List<AutoTag.Proposal>>(emptyList()) }
    var showCandidates by remember { mutableStateOf(false) }

    val working = saving || artBusy || autoBusy

    // Every cache file this dialog created, dropped when it closes.
    val scratch = remember(record.id) { mutableListOf<File>() }
    DisposableEffect(record.id) {
        onDispose { scratch.forEach { it.delete() } }
    }

    fun adopt(file: File) {
        scratch += file
        cover = file
    }

    // The system photo picker needs no storage permission, so a cover can come straight
    // from the gallery. Its fallback on older phones is the document picker, which works
    // the same way from here.
    val pickCover = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        showArt = false
        scope.launch {
            artBusy = true
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(
                        context.cacheDir,
                        "cover_pick_${System.currentTimeMillis()}.img",
                    )
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target.takeIf { it.length() > 0L }
                }.getOrNull()
            }
            artBusy = false
            if (file != null) {
                adopt(file)
                existingArt = null
                coverUrl = null
            } else {
                onMessage(context.getString(R.string.tag_art_failed))
            }
        }
    }

    fun loadOptions() {
        if (optionsLoading) return
        optionsLoading = true
        scope.launch {
            val proposal = runCatching {
                AutoTag.propose(title, artist, record.thumbnailUrl)
            }.getOrNull()
            val found = proposal?.covers.orEmpty().toMutableList()
            val thumbnail = record.thumbnailUrl
            if (!thumbnail.isNullOrBlank() && found.none { it.url == thumbnail }) {
                found += AutoTag.CoverChoice(thumbnail, "video")
            }
            options = found
            optionsLoading = false
        }
    }

    fun applyProposal(proposal: AutoTag.Proposal) {
        scope.launch {
            autoBusy = true
            try {
                title = proposal.title.ifBlank { title }
                artist = proposal.artist.ifBlank { artist }
                album = proposal.album.ifBlank { album }
                albumArtist = proposal.albumArtist.ifBlank { albumArtist }
                genre = proposal.genre.ifBlank { genre }
                year = proposal.year.ifBlank { year }
                track = proposal.track.ifBlank { track }
                options = proposal.covers

                for (choice in proposal.covers) {
                    val file = AutoTag.cacheCover(context, choice.url) ?: continue
                    adopt(file)
                    existingArt = null
                    coverUrl = choice.url
                    break
                }

                val lyrics = runCatching {
                    LyricsClient.fetch(
                        artist = proposal.artist,
                        title = proposal.title,
                        durationSeconds = record.durationSeconds,
                    )
                }.getOrNull()
                if (lyrics != null) LyricsRepository.get(context).put(record.id, lyrics)

                onMessage(context.getString(R.string.tag_autotag_done))
            } finally {
                autoBusy = false
            }
        }
    }

    fun runAutoTag() {
        if (autoBusy) return
        autoBusy = true
        scope.launch {
            try {
                val list = runCatching {
                    AutoTag.proposals(title, artist, record.thumbnailUrl)
                }.getOrDefault(emptyList())

                when {
                    list.isEmpty() -> onMessage(context.getString(R.string.tag_autotag_failed))
                    list.size == 1 -> applyProposal(list.first())
                    else -> {
                        candidates = list
                        showCandidates = true
                        autoBusy = false
                    }
                }
            } finally {
                autoBusy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.tag_title),
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    onClick = { runAutoTag() },
                    enabled = !working,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.height(30.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (autoBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = stringResource(R.string.tag_autotag),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ArtworkPicker(
                    cover = cover,
                    url = if (cover == null) existingArt else null,
                    enabled = !working,
                    onClick = {
                        loadOptions()
                        showArt = true
                    },
                )

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
                    text = stringResource(R.string.tag_cover_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (working) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(
                                if (saving) R.string.tag_saving else R.string.tag_cover_loading
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                        if (saved) {
                            // Prefer a local copy so the library keeps showing it offline.
                            val art = cover?.let {
                                AutoTag.persistCover(context, it, record.id)
                            } ?: coverUrl
                            if (!art.isNullOrBlank()) {
                                val current = HistoryRepository.get(context).items.value
                                    .firstOrNull { it.id == record.id }
                                if (current != null) {
                                    HistoryRepository.get(context)
                                        .update(current.copy(thumbnailUrl = art))
                                }
                            }
                        }
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
                Text(stringResource(R.string.tag_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) {
                Text(stringResource(R.string.cancel))
            }
        },
    )

    if (showArt) {
        CoverSheet(
            options = options,
            loading = optionsLoading,
            hasCover = cover != null,
            onPick = { choice ->
                showArt = false
                scope.launch {
                    artBusy = true
                    val file = AutoTag.cacheCover(context, choice.url)
                    artBusy = false
                    if (file != null) {
                        adopt(file)
                        existingArt = null
                        coverUrl = choice.url
                    } else {
                        onMessage(context.getString(R.string.tag_art_failed))
                    }
                }
            },
            onClear = {
                cover = null
                coverUrl = null
                existingArt = null
                showArt = false
            },
            onPickFromDevice = {
                pickCover.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onDismiss = { showArt = false },
        )
    }

    if (showCandidates) {
        CandidateSheet(
            candidates = candidates,
            onPick = { proposal ->
                showCandidates = false
                applyProposal(proposal)
            },
            onDismiss = { showCandidates = false },
        )
    }
}

/** The tile itself is the button; the plus badge only hints at it. */
@Composable
private fun ArtworkPicker(
    cover: File?,
    url: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(124.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        CoverPreview(cover = cover, url = url, modifier = Modifier.fillMaxSize())
        if (cover == null && url == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Shown when Auto(rc) finds more than one plausible release. The title, artist, album
 * and year are the disambiguators, with that candidate's cover beside them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CandidateSheet(
    candidates: List<AutoTag.Proposal>,
    onPick: (AutoTag.Proposal) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.tag_choose_match),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.tag_choose_match_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(candidates) { _, proposal ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onPick(proposal) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AsyncImage(
                            model = proposal.covers.firstOrNull()?.url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = proposal.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = listOfNotNull(
                                    proposal.artist.takeIf { it.isNotBlank() },
                                    proposal.album.takeIf { it.isNotBlank() },
                                    proposal.year.takeIf { it.isNotBlank() },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoverSheet(
    options: List<AutoTag.CoverChoice>,
    loading: Boolean,
    hasCover: Boolean,
    onPick: (AutoTag.CoverChoice) -> Unit,
    onClear: () -> Unit,
    onPickFromDevice: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.tag_cover_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            // Available even when the catalogue has nothing for this title.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onPickFromDevice() }
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.tag_cover_pick),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            when {
                loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.tag_cover_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                options.isEmpty() -> Text(
                    text = stringResource(R.string.tag_cover_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(options, key = { it.url }) { choice ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AsyncImage(
                                model = choice.url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(104.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onPick(choice) },
                            )
                            Text(
                                text = stringResource(
                                    if (choice.source == "video") {
                                        R.string.tag_cover_video
                                    } else {
                                        R.string.tag_cover_album
                                    }
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (hasCover) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.tag_cover_clear))
                }
            }
        }
    }
}

/**
 * The chosen cover, the record's current artwork, or the placeholder in that order, so
 * reopening the editor shows whatever is on the song right now.
 */
@Composable
private fun CoverPreview(cover: File?, url: String?, modifier: Modifier = Modifier) {
    val decoded by produceState<ImageBitmap?>(null, cover) {
        value = cover?.let { file -> withContext(Dispatchers.IO) { decodePreview(file) } }
    }

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = decoded
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            url != null -> AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            else -> Icon(
                painter = painterResource(R.drawable.ic_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** Decodes at a sample size, because the source can be a full-size cover. */
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

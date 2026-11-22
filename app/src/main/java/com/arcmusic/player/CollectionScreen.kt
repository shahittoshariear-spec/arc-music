package com.arcmusic.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arcmusic.R
import com.arcmusic.downloader.MediaInfo
import com.arcmusic.ui.MainViewModel

/**
 * A playlist or feed opened from the signed-in library, rendered natively. yt-dlp
 * supplies the rows; nothing here is a web view.
 */
@Composable
fun CollectionScreen(viewModel: MainViewModel) {
    val title by viewModel.collectionTitle.collectAsStateWithLifecycle()
    val tracks by viewModel.collectionTracks.collectAsStateWithLifecycle()
    val loading by viewModel.collectionLoading.collectAsStateWithLifecycle()
    val url by viewModel.collectionUrl.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val library by viewModel.items.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val preparing by viewModel.preparingTrack.collectAsStateWithLifecycle()
    val autoFormat by viewModel.autoFormat.collectAsStateWithLifecycle()
    val defaultFormat by viewModel.format.collectAsStateWithLifecycle()

    val name = title ?: return
    var pendingFormat by remember { mutableStateOf<MediaInfo?>(null) }
    val enterX = rememberPageEnter()

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = enterX
                alpha = 1f - (enterX / 300f).coerceIn(0f, 0.35f)
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = viewModel::closeCollection) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = stringResource(R.string.close),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (loading) {
                            stringResource(R.string.browse_loading)
                        } else {
                            stringResource(R.string.player_track_count, tracks.size)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                url?.takeIf { !loading && tracks.isNotEmpty() }?.let { link ->
                    IconButton(onClick = { viewModel.downloadPlaylist(link) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = stringResource(R.string.player_download_all),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (!loading && tracks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.player_collection_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(tracks, key = { index, track -> "$index:${track.url}" }) { _, track ->
                        CollectionRow(
                            info = track,
                            visual = downloadVisualFor(track, queue, library),
                            playing = playback.current?.key == track.url && playback.playing,
                            preparing = preparing == track.url,
                            onPlay = { viewModel.playSearchResult(track) },
                            onDownload = {
                                if (autoFormat) {
                                    viewModel.downloadSearchResult(track, defaultFormat)
                                } else {
                                    pendingFormat = track
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    pendingFormat?.let { info ->
        FormatSheet(
            onDismiss = { pendingFormat = null },
            onPick = { format ->
                viewModel.downloadSearchResult(info, format)
                pendingFormat = null
            },
        )
    }
}

@Composable
private fun CollectionRow(
    info: MediaInfo,
    visual: DownloadVisual,
    playing: Boolean,
    preparing: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = glassRowColor(),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .pressable(onClick = onPlay),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box {
                Artwork(info.thumbnailUrl, 54.dp, RoundedCornerShape(12.dp))
                if (playing || preparing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (preparing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_pause),
                                contentDescription = stringResource(R.string.player_pause),
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = info.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = collectionSubtitle(info),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            DownloadDial(state = visual, onClick = onDownload)
        }
    }
}

private fun collectionSubtitle(info: MediaInfo): String {
    val clock = formatClock(info.durationSeconds)
    return listOfNotNull(
        info.uploader?.takeIf { it.isNotBlank() },
        clock.takeIf { it != "0:00" },
    ).joinToString(" · ").ifBlank { "--:--" }
}

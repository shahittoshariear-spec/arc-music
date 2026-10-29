package com.arcmusic.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcmusic.R
import com.arcmusic.downloader.FailureKind
import com.arcmusic.downloader.JobKind
import com.arcmusic.downloader.JobStatus
import com.arcmusic.downloader.QueueItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    state: PlaybackState,
    onDismiss: () -> Unit,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
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
        ) {
            Text(
                text = stringResource(R.string.player_up_next),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(8.dp))

            if (state.queue.isEmpty()) {
                Text(
                    text = stringResource(R.string.player_queue_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(state.queue, key = { index, track -> "$index:${track.key}" }) { index, track ->
                        val current = index == state.index
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPlay(index) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(18.dp),
                            )
                            Artwork(track.artUrl, 40.dp, RoundedCornerShape(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (current) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = track.artist.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { onRemove(index) }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = stringResource(R.string.player_remove_from_queue),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsSheet(
    queue: List<QueueItem>,
    onDismiss: () -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onClearFinished: () -> Unit,
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
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.player_downloads),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (queue.any { !it.isActive }) {
                    TextButton(onClick = onClearFinished) {
                        Text(stringResource(R.string.player_clear_finished))
                    }
                }
            }

            if (queue.isEmpty()) {
                Text(
                    text = stringResource(R.string.player_downloads_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(queue, key = { _, item -> item.id }) { _, item ->
                        DownloadRow(item = item, onCancel = onCancel, onRetry = onRetry)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: QueueItem,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title.ifBlank { item.url },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = downloadStatus(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.status == JobStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.caveat?.let { caveat ->
                    Text(
                        text = caveat,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when {
                item.isActive -> TextButton(onClick = { onCancel(item.id) }) {
                    Text(stringResource(R.string.cancel))
                }

                item.status == JobStatus.FAILED || item.status == JobStatus.CANCELED ->
                    TextButton(onClick = { onRetry(item.id) }) {
                        Text(stringResource(R.string.retry))
                    }

                else -> Unit
            }
        }

        if (item.status == JobStatus.RUNNING) {
            val progress = item.displayProgress
            if (progress >= 0f) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun downloadStatus(item: QueueItem): String = when (item.status) {
    JobStatus.QUEUED -> stringResource(R.string.status_queued)

    JobStatus.RUNNING -> when {
        item.kind == JobKind.PLAYLIST && item.itemIndex > 0 ->
            stringResource(R.string.playlist_song_of, item.itemIndex, item.itemTotal)

        else -> stringResource(R.string.status_running)
    }

    JobStatus.FINISHED -> if (item.savedCount > 1) {
        stringResource(R.string.playlist_saved, item.savedCount)
    } else {
        stringResource(R.string.status_done)
    }

    JobStatus.FAILED -> item.errorKind?.let { stringResource(failureText(it)) }
        ?: stringResource(R.string.status_failed)

    JobStatus.CANCELED -> stringResource(R.string.status_canceled)
}

private fun failureText(kind: FailureKind): Int = when (kind) {
    FailureKind.SIGN_IN_REQUIRED -> R.string.err_sign_in
    FailureKind.AGE_RESTRICTED -> R.string.err_age_restricted
    FailureKind.STALE_EXTRACTOR -> R.string.err_stale_engine
    FailureKind.NETWORK -> R.string.err_network
    FailureKind.UNAVAILABLE -> R.string.err_unavailable
    FailureKind.UNKNOWN -> R.string.notif_failed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepSheet(
    active: Boolean,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.player_sleep),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            val options = listOf<Int?>(null, 15, 30, 60)
            options.forEach { minutes ->
                val selected = if (minutes == null) !active else false
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPick(minutes) }
                        .padding(vertical = 14.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_timer),
                        contentDescription = null,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = if (minutes == null) {
                            stringResource(R.string.player_sleep_off)
                        } else {
                            stringResource(R.string.player_sleep_minutes, minutes)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

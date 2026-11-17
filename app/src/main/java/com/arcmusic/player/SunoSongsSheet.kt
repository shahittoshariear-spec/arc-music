package com.arcmusic.player

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arcmusic.R
import com.arcmusic.downloader.SunoClip
import com.arcmusic.ui.MainViewModel

/**
 * The signed-in Suno library. Each row is one generated song; downloading it fetches the
 * audio and its cover, and stores the lyrics Suno already knows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SunoSongsSheet(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onSignIn: () -> Unit,
) {
    val signedIn by viewModel.sunoSignedIn.collectAsStateWithLifecycle()
    val artist by viewModel.sunoArtist.collectAsStateWithLifecycle()
    val clips by viewModel.sunoClips.collectAsStateWithLifecycle()
    val loading by viewModel.sunoLoading.collectAsStateWithLifecycle()
    val error by viewModel.sunoError.collectAsStateWithLifecycle()
    val progress by viewModel.sunoProgress.collectAsStateWithLifecycle()
    // Collected so a finished download immediately turns its row into a check mark.
    val library by viewModel.items.collectAsStateWithLifecycle()

    LaunchedEffect(signedIn) {
        if (signedIn && clips.isEmpty() && !loading) viewModel.loadSunoClips()
    }

    val saved = remember(clips, library) { clips.associateWith(viewModel::isSunoSaved) }
    val unsaved = saved.count { !it.value }

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
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.suno_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = artist?.let {
                            stringResource(R.string.suno_signed_in, it)
                        } ?: stringResource(R.string.suno_my_songs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (signedIn) {
                    IconButton(onClick = { viewModel.loadSunoClips() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.suno_refresh),
                        )
                    }
                    IconButton(
                        onClick = { viewModel.downloadAllSuno() },
                        enabled = unsaved > 0,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = stringResource(R.string.suno_download_all),
                        )
                    }
                }
            }
            Spacer(Modifier.size(10.dp))

            when {
                !signedIn -> {
                    Text(
                        text = stringResource(R.string.suno_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = onSignIn) {
                        Text(stringResource(R.string.suno_sign_in))
                    }
                }

                loading && clips.isEmpty() -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.suno_loading),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                error != null -> {
                    Text(
                        text = error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { viewModel.loadSunoClips() }) {
                            Text(stringResource(R.string.suno_retry))
                        }
                        TextButton(onClick = onSignIn) {
                            Text(stringResource(R.string.suno_sign_in_again))
                        }
                    }
                }

                clips.isEmpty() -> Text(
                    text = stringResource(R.string.suno_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(clips, key = { it.id }) { clip ->
                        SunoRow(
                            clip = clip,
                            saved = saved[clip] == true,
                            progress = progress[clip.id],
                            onDownload = { viewModel.downloadSunoClip(clip) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SunoRow(
    clip: SunoClip,
    saved: Boolean,
    progress: Float?,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(clip.coverUrl, 46.dp, RoundedCornerShape(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = clip.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (clip.durationSeconds > 0) append(clock(clip.durationSeconds))
                    if (!clip.tags.isNullOrBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(clip.tags.take(40))
                    }
                }.ifBlank { clip.artist },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            saved -> Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.suno_saved),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            progress != null -> CircularProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .size(22.dp),
                strokeWidth = 2.dp,
            )

            else -> IconButton(onClick = onDownload) {
                Icon(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = stringResource(R.string.suno_download),
                )
            }
        }
    }
}

private fun clock(seconds: Long): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    return "$minutes:${rest.toString().padStart(2, '0')}"
}

package com.arcmusic.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.arcmusic.R
import com.arcmusic.ui.LyricsState

/**
 * Full-screen lyrics, timed when the source provides LRC: the current line brightens and
 * the list follows it. Plain text still displays, just without following the song.
 */
@Composable
fun LyricsScreen(
    track: PlayableTrack,
    state: LyricsState,
    positionMs: Long,
    onClose: () -> Unit,
    onFetch: () -> Unit,
    onSave: (String) -> Unit,
) {
    var showEditor by remember { mutableStateOf(false) }
    val lines = remember(state.raw) { state.raw?.let(::parseLyrics).orEmpty() }
    val currentIndex = remember(lines, positionMs) { currentLyricIndex(lines, positionMs) }
    val listState = rememberLazyListState()

    LaunchedEffect(currentIndex) {
        if (currentIndex in lines.indices && lines.size > 4) {
            listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF2A0A10), Color.Black)))
        )
        if (!track.artUrl.isNullOrBlank()) {
            AsyncImage(
                model = track.artUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(80.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.88f),
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White,
                    )
                }
                Text(
                    text = stringResource(R.string.player_lyrics),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showEditor = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = stringResource(R.string.player_lyrics_edit),
                        tint = Color.White,
                    )
                }
            }

            when {
                state.loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                lines.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_lyrics),
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.45f),
                            modifier = Modifier.size(52.dp),
                        )
                        Spacer(Modifier.size(14.dp))
                        Text(
                            text = state.error ?: stringResource(R.string.player_lyrics_empty),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = stringResource(R.string.player_lyrics_empty_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.size(20.dp))
                        Button(onClick = onFetch) {
                            Icon(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.player_lyrics_find))
                        }
                        TextButton(onClick = { showEditor = true }) {
                            Text(
                                text = stringResource(R.string.player_lyrics_edit),
                                color = Color.White.copy(alpha = 0.8f),
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 90.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        itemsIndexed(lines) { index, line ->
                            val active = index == currentIndex
                            val alpha by animateFloatAsState(
                                targetValue = if (active) 1f else 0.42f,
                                animationSpec = tween(320),
                                label = "lyricAlpha",
                            )
                            Text(
                                text = line.text.ifBlank { "♪" },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = Color.White.copy(alpha = alpha),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onFetch) {
                            Text(
                                text = stringResource(R.string.player_lyrics_find),
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showEditor = true }) {
                            Text(
                                text = stringResource(R.string.player_lyrics_edit),
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        LyricsEditorDialog(
            initial = state.raw.orEmpty(),
            onDismiss = { showEditor = false },
            onSave = { text ->
                showEditor = false
                onSave(text)
            },
        )
    }
}

@Composable
private fun LyricsEditorDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_lyrics_edit)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 8,
                maxLines = 14,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text(stringResource(R.string.tag_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

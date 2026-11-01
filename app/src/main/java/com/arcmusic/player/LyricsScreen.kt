package com.arcmusic.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import com.arcmusic.R
import com.arcmusic.data.LyricsStyle
import com.arcmusic.ui.LyricsState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen lyrics, timed when the source provides LRC: the current line brightens and
 * the list follows it. Plain text still displays, just without following the song.
 */
@Composable
fun LyricsScreen(
    track: PlayableTrack,
    state: LyricsState,
    positionMs: Long,
    style: LyricsStyle,
    tapToSeek: Boolean,
    onSeekTo: (Long) -> Unit,
    onClose: () -> Unit,
    onFetch: () -> Unit,
    onSave: (String) -> Unit,
) {
    var showEditor by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lines = remember(state.raw) { state.raw?.let(::parseLyrics).orEmpty() }
    val currentIndex = remember(lines, positionMs) { currentLyricIndex(lines, positionMs) }
    val listState = rememberLazyListState()

    // Pulling the sheet down closes it, the mirror of how it opened. The drag is
    // tracked only when the lyric list is already at its top, so scrolling still wins.
    val dismissThreshold = with(LocalDensity.current) { 140.dp.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var pullRaw by remember { mutableFloatStateOf(0f) }
    val pull by animateFloatAsState(
        targetValue = if (dragging) pullRaw else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "lyricsPull",
    )
    val scope = rememberCoroutineScope()
    // A plain holder, not state: this is written on every drag delta and never read
    // during composition, so keeping it in mutableStateOf recomposed the whole screen
    // at the scroll rate for nothing.
    val settleJob = remember { java.util.concurrent.atomic.AtomicReference<Job?>(null) }

    fun settle() {
        val close = pullRaw > dismissThreshold
        dragging = false
        pullRaw = 0f
        if (close) onClose()
    }
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && pullRaw > 0f) {
                    dragging = false
                    pullRaw = 0f
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y > 0f) {
                    dragging = true
                    pullRaw = (pullRaw + available.y).coerceAtLeast(0f)
                    // A drag can end without a fling (released while still), and the
                    // nested-scroll API has no drag-end callback, so settle when the
                    // stream of deltas stops. The window is generous on purpose: a short
                    // pause mid-pull used to dismiss the sheet under a resting thumb.
                    settleJob.getAndSet(
                        scope.launch {
                            delay(450)
                            settle()
                        }
                    )?.cancel()
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                settleJob.getAndSet(null)?.cancel()
                settle()
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(currentIndex, style) {
        if (currentIndex in lines.indices && lines.size > 4) {
            // Spotlight scrolls the current line to the middle (the list's top padding is
            // half the viewport); the other styles keep it two lines above centre.
            val target = if (style == LyricsStyle.SPOTLIGHT) {
                currentIndex
            } else {
                (currentIndex - 2).coerceAtLeast(0)
            }
            listState.animateScrollToItem(target)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = pull }
            .nestedScroll(scrollConnection)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            lerp(MaterialTheme.colorScheme.primary, Color.Black, 0.82f),
                            Color.Black,
                        )
                    )
                )
        )
        if (!track.artUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(track.artUrl)
                    .size(96, 96)
                    .transformations(SoftenTransformation(radius = 5, passes = 4))
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize(),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        // The header is not scrollable, so it gets its own drag.
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true },
                            onVerticalDrag = { change, delta ->
                                change.consume()
                                if (delta > 0f) {
                                    pullRaw = (pullRaw + delta).coerceAtLeast(0f)
                                }
                            },
                            onDragEnd = {
                                settle()
                            },
                            onDragCancel = {
                                dragging = false
                                pullRaw = 0f
                            },
                        )
                    },
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
                    val timed = (lines.firstOrNull()?.timeMs ?: -1L) >= 0L
                    Box(modifier = Modifier.fillMaxSize()) {
                        when {
                            !timed || style == LyricsStyle.CLASSIC -> LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 90.dp, bottom = 124.dp),
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
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(
                                                enabled = timed && tapToSeek && line.timeMs >= 0L,
                                            ) { onSeekTo(line.timeMs) },
                                    )
                                }
                            }

                            style == LyricsStyle.RIVER -> RiverLyrics(
                                lines = lines,
                                currentIndex = currentIndex,
                                positionMs = positionMs,
                            )

                            style == LyricsStyle.SPOTLIGHT -> SpotlightLyrics(
                                lines = lines,
                                currentIndex = currentIndex,
                                listState = listState,
                                tapToSeek = tapToSeek,
                                onSeekTo = onSeekTo,
                            )

                            else -> FloatingLyrics(lines = lines, currentIndex = currentIndex)
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.55f),
                                            Color.Black.copy(alpha = 0.90f),
                                        )
                                    )
                                )
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilledTonalButton(
                                    onClick = onFetch,
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color.White.copy(alpha = 0.14f),
                                        contentColor = Color.White,
                                    ),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_search),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(R.string.player_lyrics_find))
                                }
                                Spacer(Modifier.weight(1f))
                                FilledTonalButton(
                                    onClick = { showEditor = true },
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color.White.copy(alpha = 0.14f),
                                        contentColor = Color.White,
                                    ),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_edit),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(R.string.player_lyrics_edit))
                                }
                            }
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

/**
 * Spotlight: the current line sits in the middle of the screen at full contrast while
 * everything else fades to forty percent. The list's half-screen padding is what makes
 * `animateScrollToItem` land the line in the centre.
 */
@Composable
private fun SpotlightLyrics(
    lines: List<LyricLine>,
    currentIndex: Int,
    listState: LazyListState,
    tapToSeek: Boolean,
    onSeekTo: (Long) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val padding = (maxHeight / 2 - 40.dp).coerceAtLeast(24.dp)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding, bottom = padding),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(lines) { index, line ->
                val active = index == currentIndex
                val alpha by animateFloatAsState(
                    targetValue = if (active) 1f else 0.4f,
                    animationSpec = tween(320),
                    label = "spotAlpha",
                )
                val scale by animateFloatAsState(
                    targetValue = if (active) 1f else 0.94f,
                    animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                    label = "spotScale",
                )
                Text(
                    text = line.text.ifBlank { "♪" },
                    style = if (active) {
                        MaterialTheme.typography.headlineSmall
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = Color.White.copy(alpha = alpha),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .clickable(
                            enabled = tapToSeek && line.timeMs >= 0L,
                        ) { onSeekTo(line.timeMs) }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                )
            }
        }
    }
}

/**
 * River: one line at a time, the words lighting up as they are sung. The progress is
 * eased between position updates so the highlight flows instead of stepping.
 */
@Composable
private fun RiverLyrics(lines: List<LyricLine>, currentIndex: Int, positionMs: Long) {
    val index = currentIndex.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
    val line = lines.getOrNull(index) ?: return
    val nextStart = lines.getOrNull(index + 1)?.timeMs ?: (line.timeMs + 4_000L)
    val span = (nextStart - line.timeMs).coerceAtLeast(1L)
    val target = ((positionMs - line.timeMs).toFloat() / span).coerceIn(0f, 1f)
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(360, easing = LinearEasing),
        label = "riverFlow",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 30.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = riverText(line.text.ifBlank { "♪" }, fraction),
            style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 36.sp),
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

private val RiverWatermark = Color.White.copy(alpha = 0.3f)

private fun riverText(text: String, fraction: Float) = buildAnnotatedString {
    val words = text.split(' ')
    val total = words.sumOf { it.length }.coerceAtLeast(1)
    var consumed = 0
    words.forEachIndexed { index, word ->
        val start = consumed.toFloat() / total
        val end = (consumed + word.length).toFloat() / total
        val local = ((fraction - start) / (end - start).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        withStyle(SpanStyle(color = lerp(RiverWatermark, Color.White, local))) {
            append(word)
        }
        if (index != words.lastIndex) append(' ')
        consumed += word.length
    }
}

/**
 * Floating: the current line sharp in the middle, the lines around it blurred further
 * the farther they are, Apple Music style.
 */
@Composable
private fun FloatingLyrics(lines: List<LyricLine>, currentIndex: Int) {
    AnimatedContent(
        targetState = currentIndex,
        transitionSpec = {
            (
                fadeIn(tween(220)) +
                    slideInVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) {
                        it / 5
                    }
                ) togetherWith (
                fadeOut(tween(200)) +
                    slideOutVertically(tween(220)) { -it / 5 }
                )
        },
        label = "floatingLyrics",
    ) { index ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (offset in -2..2) {
                val line = lines.getOrNull(index + offset) ?: continue
                val distance = kotlin.math.abs(offset)
                val alpha = when (distance) {
                    0 -> 1f
                    1 -> 0.55f
                    else -> 0.32f
                }
                // Blur is expensive per re-layout on a screen that scrolls every line;
                // fading the neighbouring lines reads the same and costs nothing.
                Text(
                    text = line.text.ifBlank { "♪" },
                    style = if (distance == 0) {
                        MaterialTheme.typography.headlineSmall
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                    fontWeight = if (distance == 0) FontWeight.Bold else FontWeight.Medium,
                    color = Color.White.copy(alpha = alpha),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                )
            }
        }
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

package com.arcmusic.player

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.arcmusic.R
import com.arcmusic.ui.BrowserScreen
import com.arcmusic.ui.MainViewModel
import kotlinx.coroutines.launch

/** The three pillars of the app. Search sits in the middle of the pill. */
enum class PlayerTab(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    LIBRARY(R.string.player_tab_library, R.drawable.ic_library),
    SEARCH(R.string.player_tab_search, R.drawable.ic_search),
    SETTINGS(R.string.player_tab_settings, R.drawable.ic_settings),
}

@Composable
fun PlayerScreen(
    viewModel: MainViewModel,
    onRequestNotificationPermission: () -> Unit,
) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val focusSearch by viewModel.focusSearch.collectAsStateWithLifecycle()
    val collectionTitle by viewModel.collectionTitle.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val sleepEndsAt by viewModel.sleepEndsAt.collectAsStateWithLifecycle()
    val library by viewModel.items.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val openPlaylistId by viewModel.openPlaylistId.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyrics.collectAsStateWithLifecycle()
    val openPlaylist = remember(playlists, openPlaylistId) {
        playlists.firstOrNull { it.id == openPlaylistId }
    }

    var tab by rememberSaveable { mutableStateOf(PlayerTab.LIBRARY) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var renamingPlaylist by remember { mutableStateOf(false) }

    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHost.showSnackbar(message) }
    }

    LaunchedEffect(Unit) { onRequestNotificationPermission() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbarHost.showSnackbar(it) } }
    LaunchedEffect(focusSearch) { if (focusSearch > 0) tab = PlayerTab.SEARCH }

    // The full player is an overlay rather than a second activity, so back has to
    // close it instead of leaving the app.
    BackHandler(enabled = showNowPlaying) { showNowPlaying = false }
    BackHandler(enabled = collectionTitle != null) { viewModel.closeCollection() }
    BackHandler(enabled = openPlaylist != null) { viewModel.closePlaylist() }
    BackHandler(enabled = showLyrics) { showLyrics = false }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceContainerLow,
                    )
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(snackbarHost) },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AnimatedVisibility(
                        visible = playback.current != null,
                        enter = slideInVertically(animationSpec = tween(280)) { it } + fadeIn(tween(200)),
                        exit = slideOutVertically(animationSpec = tween(220)) { it } + fadeOut(tween(160)),
                    ) {
                        playback.current?.let { track ->
                            MiniPlayer(
                                track = track,
                                playing = playback.playing,
                                buffering = playback.buffering,
                                progress = playback.progress,
                                onToggle = viewModel::togglePlayback,
                                onOpen = { showNowPlaying = true },
                            )
                        }
                    }
                    PillNav(selected = tab, onSelect = { tab = it })
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val direction = if (forward) 1 else -1
                        (
                            fadeIn(tween(220)) +
                                slideInHorizontally(tween(280)) { it / 12 * direction }
                            ) togetherWith (
                            fadeOut(tween(150)) +
                                slideOutHorizontally(tween(220)) { -it / 12 * direction }
                            )
                    },
                    label = "tab",
                ) { current ->
                    when (current) {
                        PlayerTab.LIBRARY -> LibraryTab(
                            viewModel = viewModel,
                            onGoSearch = { tab = PlayerTab.SEARCH },
                            onOpenBrowser = { showBrowser = true },
                            onMessage = showMessage,
                        )

                        PlayerTab.SEARCH -> SearchTab(
                            viewModel = viewModel,
                            onOpenBrowser = { showBrowser = true },
                        )

                        PlayerTab.SETTINGS -> SettingsTab(
                            viewModel = viewModel,
                            onOpenBrowser = { showBrowser = true },
                            onMessage = showMessage,
                        )
                    }
                }
            }
        }

        if (collectionTitle != null) {
            CollectionScreen(viewModel)
        }

        if (openPlaylist != null) {
            val records = remember(openPlaylist.trackIds, library) {
                openPlaylist.trackIds.mapNotNull { id -> library.firstOrNull { it.id == id } }
            }
            PlaylistScreen(
                playlist = openPlaylist,
                records = records,
                currentKey = playback.current?.key,
                playing = playback.playing,
                onClose = viewModel::closePlaylist,
                onPlay = { index -> viewModel.playRecords(records, index) },
                onRemove = { record -> viewModel.removeFromPlaylist(openPlaylist.id, record.id) },
                onRename = { renamingPlaylist = true },
                onDelete = { viewModel.deletePlaylist(openPlaylist.id) },
            )
        }

        if (renamingPlaylist && openPlaylist != null) {
            RenamePlaylistDialog(
                current = openPlaylist.name,
                onDismiss = { renamingPlaylist = false },
                onRename = { name ->
                    viewModel.renamePlaylist(openPlaylist.id, name)
                    renamingPlaylist = false
                },
            )
        }

        if (showLyrics) {
            playback.current?.let { track ->
                LyricsScreen(
                    track = track,
                    state = lyricsState,
                    positionMs = playback.positionMs,
                    onClose = { showLyrics = false },
                    onFetch = { viewModel.fetchLyrics(track) },
                    onSave = viewModel::saveLyrics,
                )
            }
        }

        AnimatedVisibility(
            visible = showNowPlaying && playback.current != null,
            enter = slideInVertically(animationSpec = tween(340)) { it } + fadeIn(tween(240)),
            exit = slideOutVertically(animationSpec = tween(280)) { it } + fadeOut(tween(180)),
        ) {
            playback.current?.let { track ->
                NowPlayingScreen(
                    state = playback,
                    track = track,
                    isFavorite = favorites.contains(track.key),
                    onClose = { showNowPlaying = false },
                    onToggle = viewModel::togglePlayback,
                    onNext = viewModel::nextTrack,
                    onPrevious = viewModel::previousTrack,
                    onSeek = viewModel::seekTo,
                    onToggleShuffle = viewModel::toggleShuffle,
                    onCycleRepeat = viewModel::cycleRepeat,
                    onToggleFavorite = { viewModel.toggleFavorite(track.key) },
                    onOpenLyrics = {
                        viewModel.openLyrics(track)
                        showLyrics = true
                    },
                    onOpenQueue = { showQueue = true },
                    onOpenSleep = { showSleep = true },
                )
            }
        }

        if (showQueue) {
            QueueSheet(
                state = playback,
                onDismiss = { showQueue = false },
                onPlay = { index ->
                    viewModel.playFromQueue(index)
                    showQueue = false
                },
                onRemove = viewModel::removeFromQueue,
            )
        }

        if (showSleep) {
            SleepSheet(
                active = sleepEndsAt != null,
                onDismiss = { showSleep = false },
                onPick = { minutes ->
                    viewModel.setSleepTimer(minutes)
                    showSleep = false
                },
            )
        }

        if (showBrowser) {
            BrowserScreen(
                onDismiss = { showBrowser = false },
                onSessionChanged = { viewModel.refreshSession() },
                onMessage = showMessage,
            )
        }
    }
}

@Composable
private fun PillNav(selected: PlayerTab, onSelect: (PlayerTab) -> Unit) {
    GlassSurface(shape = CircleShape, strong = true) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PlayerTab.entries.forEach { item ->
                val active = item == selected
                val background by animateColorAsState(
                    targetValue = if (active) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        Color.Transparent
                    },
                    animationSpec = tween(260),
                    label = "navItem",
                )
                val iconScale by animateFloatAsState(
                    targetValue = if (active) 1f else 0.9f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "navIcon",
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(background)
                        .clickable { onSelect(item) }
                        .padding(vertical = 11.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(item.icon),
                        contentDescription = null,
                        tint = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .size(20.dp)
                            .scale(iconScale),
                    )
                    AnimatedVisibility(visible = active) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(item.label),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    track: PlayableTrack,
    playing: Boolean,
    buffering: Boolean,
    progress: Float,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    GlassSurface(
        shape = RoundedCornerShape(20.dp),
        strong = true,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Crossfade(
                    targetState = track.artUrl,
                    animationSpec = tween(320),
                    label = "miniArt",
                ) { art ->
                    Artwork(art, 44.dp, RoundedCornerShape(12.dp))
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
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
                if (buffering) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    IconButton(onClick = onToggle) {
                        AnimatedContent(
                            targetState = playing,
                            transitionSpec = {
                                (fadeIn(tween(160)) + scaleIn(initialScale = 0.6f)) togetherWith
                                    (fadeOut(tween(120)) + scaleOut(targetScale = 0.6f))
                            },
                            label = "miniPlay",
                        ) { isPlaying ->
                            Icon(
                                painter = painterResource(
                                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                                ),
                                contentDescription = stringResource(
                                    if (isPlaying) R.string.player_pause else R.string.player_play
                                ),
                            )
                        }
                    }
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
        }
    }
}

@Composable
private fun NowPlayingScreen(
    state: PlaybackState,
    track: PlayableTrack,
    isFavorite: Boolean,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleep: () -> Unit,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1L)
    val fraction = drag ?: state.progress

    Box(modifier = Modifier.fillMaxSize()) {
        // Immersive backdrop: the artwork itself, blurred, under a dark scrim. The
        // blur is a no-op below API 31, so the alpha keeps it readable there too.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF3A0A12), Color(0xFF000000))
                    )
                )
        )
        if (!track.artUrl.isNullOrBlank()) {
            AsyncImage(
                model = track.artUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(72.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.42f),
                            Color.Black.copy(alpha = 0.72f),
                            Color.Black.copy(alpha = 0.92f),
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
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
                    text = stringResource(R.string.player_now_playing),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(48.dp))
            }

            Spacer(Modifier.weight(0.12f))

            Artwork(
                url = track.artUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = RoundedCornerShape(28.dp),
                iconSize = 72.dp,
            )

            Spacer(Modifier.weight(0.08f))

            Text(
                text = track.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = track.artist.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(16.dp))

            Slider(
                value = fraction,
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    drag?.let { onSeek((it * duration).toLong()) }
                    drag = null
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.28f),
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatClockMillis(
                        drag?.let { (it * duration).toLong() } ?: state.positionMs
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f),
                )
                Text(
                    text = formatClockMillis(state.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f),
                )
            }

            Spacer(Modifier.weight(0.08f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shuffle),
                        contentDescription = stringResource(R.string.player_shuffle),
                        tint = if (state.shuffle) Color.White else Color.White.copy(alpha = 0.4f),
                    )
                }
                IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_previous),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(34.dp),
                    )
                }
                FilledIconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(76.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                ) {
                    Icon(
                        painter = painterResource(
                            if (state.playing) R.drawable.ic_pause else R.drawable.ic_play
                        ),
                        contentDescription = stringResource(
                            if (state.playing) R.string.player_pause else R.string.player_play
                        ),
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_next),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(34.dp),
                    )
                }
                IconButton(onClick = onCycleRepeat) {
                    Icon(
                        painter = painterResource(
                            if (state.repeatMode == RepeatMode.ONE) {
                                R.drawable.ic_repeat_one
                            } else {
                                R.drawable.ic_repeat
                            }
                        ),
                        contentDescription = stringResource(R.string.player_repeat),
                        tint = if (state.repeatMode == RepeatMode.OFF) {
                            Color.White.copy(alpha = 0.4f)
                        } else {
                            Color.White
                        },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        painter = painterResource(
                            if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite
                        ),
                        contentDescription = null,
                        tint = if (isFavorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.White.copy(alpha = 0.7f)
                        },
                    )
                }
                IconButton(onClick = onOpenLyrics) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lyrics),
                        contentDescription = stringResource(R.string.player_lyrics),
                        tint = Color.White.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.width(28.dp))
                IconButton(onClick = onOpenSleep) {
                    Icon(
                        painter = painterResource(R.drawable.ic_timer),
                        contentDescription = stringResource(R.string.player_sleep),
                        tint = Color.White.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.width(28.dp))
                IconButton(onClick = onOpenQueue) {
                    Icon(
                        painter = painterResource(R.drawable.ic_queue),
                        contentDescription = stringResource(R.string.player_up_next),
                        tint = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.weight(0.06f))
        }
    }
}

package com.arcmusic.player

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import com.arcmusic.R
import com.arcmusic.data.AudioFormat
import com.arcmusic.data.DownloadRecord
import com.arcmusic.data.LyricsStyle
import com.arcmusic.ui.BrowserScreen
import com.arcmusic.ui.LyricsState
import com.arcmusic.ui.MainViewModel
import com.arcmusic.ui.TagEditorDialog
import com.arcmusic.ui.theme.rememberArtworkGradient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The three pillars of the app. Search sits in the middle of the pill. */
enum class PlayerTab(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    LIBRARY(R.string.player_tab_library, R.drawable.ic_library),
    BROWSE(R.string.player_tab_browse, R.drawable.ic_folder),
    SEARCH(R.string.player_tab_search, R.drawable.ic_search),
    STATS(R.string.player_tab_stats, R.drawable.ic_stats),
}

/** Drag distance on the mini player that pulls the full player up. */
private const val SWIPE_UP_TO_OPEN = 24f

/** Horizontal drag distance on the mini player that closes it and stops playback. */
private const val SWIPE_LEFT_TO_DISMISS = 150f

private const val DRAG_UNKNOWN = 0
private const val DRAG_VERTICAL = 1
private const val DRAG_SIDEWAYS = 2

@Composable
fun PlayerScreen(
    viewModel: MainViewModel,
    onRequestPermissions: () -> Unit,
) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val focusSearch by viewModel.focusSearch.collectAsStateWithLifecycle()
    val collectionTitle by viewModel.collectionTitle.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val searchEnabled by viewModel.showSearchTab.collectAsStateWithLifecycle()
    val browseEnabled by viewModel.showBrowseTab.collectAsStateWithLifecycle()
    val sleepEndsAt by viewModel.sleepEndsAt.collectAsStateWithLifecycle()
    val library by viewModel.items.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val openPlaylistId by viewModel.openPlaylistId.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyrics.collectAsStateWithLifecycle()
    val lyricsStyle by viewModel.lyricsStyle.collectAsStateWithLifecycle()
    val autoFormatOn by viewModel.autoFormat.collectAsStateWithLifecycle()
    val downloadFormat by viewModel.format.collectAsStateWithLifecycle()
    val openPlaylist = remember(playlists, openPlaylistId) {
        playlists.firstOrNull { it.id == openPlaylistId }
    }

    var tab by rememberSaveable { mutableStateOf(PlayerTab.LIBRARY) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var renamingPlaylist by remember { mutableStateOf(false) }
    var ranking by remember { mutableStateOf<StatsRanking?>(null) }
    var lastRanking by remember { mutableStateOf<StatsRanking?>(null) }
    var trackMenu by remember { mutableStateOf(false) }
    // The sheet is built from the song it was opened for, not from whatever is playing
    // by the time a row is tapped: a track that ends while the menu is open used to
    // retarget every action to the next song.
    var menuTrack by remember { mutableStateOf<PlayableTrack?>(null) }
    var editingRecord by remember { mutableStateOf<DownloadRecord?>(null) }
    var addingTrack by remember { mutableStateOf<DownloadRecord?>(null) }
    var creatingPlaylistFor by remember { mutableStateOf<DownloadRecord?>(null) }

    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHost.showSnackbar(message) }
    }

    LaunchedEffect(Unit) { onRequestPermissions() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbarHost.showSnackbar(it) } }
    LaunchedEffect(focusSearch) { if (focusSearch > 0) tab = PlayerTab.SEARCH }

    val tabs = remember(searchEnabled, browseEnabled) {
        PlayerTab.entries.filter {
            (it != PlayerTab.SEARCH || searchEnabled) &&
                (it != PlayerTab.BROWSE || browseEnabled)
        }
    }
    // A shared link can still open Search while its pill is hidden; otherwise a tab that
    // was switched off in Settings must not leave the app on a page with no pill entry.
    LaunchedEffect(tabs, focusSearch) {
        if (tab == PlayerTab.BROWSE && !browseEnabled) tab = PlayerTab.LIBRARY
        if (tab == PlayerTab.SEARCH && !searchEnabled && focusSearch == 0) {
            tab = PlayerTab.LIBRARY
        }
    }

    // The full player is an overlay rather than a second activity, so back has to
    // close it instead of leaving the app. These live at the end of the composition on
    // purpose: BackHandler gives priority to whichever is registered last, and a tab
    // selection composed inside the Scaffold would otherwise swallow Back before the
    // overlay above it had a chance to close.

    // The background follows the cover art when the gradient setting is on. The blend
    // keeps it a backdrop: the accent never competes with titles or labels.
    val gradientEnabled by viewModel.artworkGradient.collectAsStateWithLifecycle()
    val artColors = rememberArtworkGradient(playback.current?.artUrl, gradientEnabled)
    val baseColor = MaterialTheme.colorScheme.background
    val gradientTop by animateColorAsState(
        targetValue = artColors?.firstOrNull()?.let { lerp(it, baseColor, 0.72f) } ?: baseColor,
        animationSpec = tween(900),
        label = "bgTop",
    )
    val gradientMid by animateColorAsState(
        targetValue = artColors?.getOrNull(1)?.let { lerp(it, baseColor, 0.84f) }
            ?: MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(900),
        label = "bgMid",
    )

    // A static glow that sits behind the top of the app. It used to drift on an
    // infinite animation, but that repainted a full-screen gradient every frame and
    // was measurable on the battery, so the shape stays and the motion is gone.
    val glow = artColors?.firstOrNull() ?: MaterialTheme.colorScheme.primary
    val glowAlpha = if (artColors != null) 0.26f else 0.10f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(gradientTop, gradientMid, baseColor)
                )
            )
            .drawBehind {
                val center = Offset(size.width * 0.5f, size.height * 0.08f)
                val radius = size.maxDimension * 0.95f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = glowAlpha), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
    ) {
        // Special editions paint behind everything, under the app's own gradient. The
        // motion only runs while the player is open; elsewhere the shapes are static.
        ThemeBackdrop(animate = showNowPlaying, modifier = Modifier.matchParentSize())

        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(snackbarHost) },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        // Rows now meet the bar with a soft fade instead of a cut. Drawn
                        // above the content because the bottom bar is composed after it.
                        .drawBehind {
                            val fade = 88.dp.toPx()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(Color.Transparent, baseColor.copy(alpha = 0.92f))
                                ),
                                topLeft = Offset(0f, -fade),
                                size = Size(size.width, fade),
                            )
                        },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AnimatedVisibility(
                        visible = playback.current != null,
                        enter = slideInVertically(
                            animationSpec = spring(
                                dampingRatio = 0.82f,
                                stiffness = Spring.StiffnessMediumLow,
                            )
                        ) { it } + fadeIn(tween(200)),
                        exit = slideOutVertically(animationSpec = tween(220)) { it } + fadeOut(tween(160)),
                    ) {
                        playback.current?.let { track ->
                            MiniPlayer(
                                track = track,
                                playing = playback.playing,
                                buffering = playback.buffering,
                                positionFlow = viewModel.position,
                                durationMs = playback.durationMs,
                                onToggle = viewModel::togglePlayback,
                                onOpen = { showNowPlaying = true },
                                onDismiss = {
                                    // Only stop if the song that was swiped is still the
                                    // one playing; a track change in the meantime must
                                    // not end the next song.
                                    if (playback.current?.key == track.key) {
                                        viewModel.stopPlayback()
                                    }
                                },
                            )
                        }
                    }
                    PillNav(selected = tab, tabs = tabs, onSelect = { tab = it })
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
                            fadeIn(tween(200)) +
                                slideInHorizontally(
                                    animationSpec = spring(
                                        dampingRatio = 0.9f,
                                        stiffness = Spring.StiffnessMediumLow,
                                    )
                                ) { it / 8 * direction }
                            ) togetherWith (
                            fadeOut(tween(140)) +
                                slideOutHorizontally(tween(240)) { -it / 10 * direction }
                            )
                    },
                    label = "tab",
                ) { current ->
                    when (current) {
                        PlayerTab.LIBRARY -> LibraryTab(
                            viewModel = viewModel,
                            searchEnabled = searchEnabled,
                            onGoSearch = { tab = PlayerTab.SEARCH },
                            onMessage = showMessage,
                            onOpenPlayer = { showNowPlaying = true },
                        )

                        PlayerTab.BROWSE -> BrowseTab(viewModel = viewModel)

                        PlayerTab.SEARCH -> SearchTab(
                            viewModel = viewModel,
                            onOpenBrowser = { showBrowser = true },
                            onOpenPlayer = { showNowPlaying = true },
                        )

                        PlayerTab.STATS -> StatsTab(
                            viewModel = viewModel,
                            onOpenSettings = { showSettings = true },
                            onOpenRanking = { ranking = it },
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally(
                animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
            ) { it / 2 } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(240)) { it / 2 } + fadeOut(tween(180)),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                SettingsTab(
                    viewModel = viewModel,
                    onOpenBrowser = { showBrowser = true },
                    onMessage = showMessage,
                    onClose = { showSettings = false },
                )
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
                onPlay = { index ->
                    val picked = records.getOrNull(index)
                    if (picked != null && picked.id == playback.current?.key) {
                        showNowPlaying = true
                    } else {
                        viewModel.playRecords(records, index)
                    }
                },
                onRemove = { record -> viewModel.removeFromPlaylist(openPlaylist.id, record.id) },
                onRename = { renamingPlaylist = true },
                onDelete = { viewModel.deletePlaylist(openPlaylist.id) },
            )
        }

        LaunchedEffect(ranking) { if (ranking != null) lastRanking = ranking }
        AnimatedVisibility(
            visible = ranking != null,
            enter = slideInHorizontally(
                animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
            ) { it / 3 } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(180)),
        ) {
            lastRanking?.let { request ->
                RankingScreen(
                    viewModel = viewModel,
                    ranking = request,
                    onClose = { ranking = null },
                )
            }
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

        AnimatedVisibility(
            visible = showNowPlaying && playback.current != null,
            // The movement lives inside NowPlayingScreen: it grows the cover out of the
            // mini player. Sliding the whole sheet here as well made the two fight.
            enter = fadeIn(tween(110)),
            // The collapse has already faded everything by the time this runs, and a
            // lingering invisible sheet would swallow taps aimed at the mini player.
            exit = fadeOut(tween(0)),
        ) {
            playback.current?.let { track ->
                NowPlayingScreen(
                    state = playback,
                    positionFlow = viewModel.position,
                    track = track,
                    accentColors = artColors,
                    isFavorite = favorites.contains(track.key),
                    onClose = { showNowPlaying = false },
                    onToggle = viewModel::togglePlayback,
                    onNext = viewModel::nextTrack,
                    onPrevious = viewModel::previousTrack,
                    onSeek = viewModel::seekTo,
                    onToggleShuffle = viewModel::toggleShuffle,
                    onCycleRepeat = viewModel::cycleRepeat,
                    onToggleFavorite = { viewModel.toggleFavorite(track.key) },
                    onOpenQueue = { showQueue = true },
                    onOpenSleep = { showSleep = true },
                    onOpenMenu = {
                        menuTrack = track
                        trackMenu = true
                    },
                    onOpenLyrics = { viewModel.openLyrics(track) },
                )
            }
        }

        menuTrack?.let { track ->
            if (trackMenu) {
                TrackActionsSheet(
                    track = track,
                    favorite = favorites.contains(track.key),
                    downloaded = library.any { it.id == track.key },
                    downloadOptions = if (autoFormatOn) {
                        AudioFormat.entries.filter { it != downloadFormat }
                    } else {
                        AudioFormat.entries
                    },
                    onDismiss = { trackMenu = false },
                    onToggleFavorite = { viewModel.toggleFavorite(track.key) },
                    onPlayNext = { PlaybackController.playNext(track) },
                    onAddToQueue = { PlaybackController.addToQueue(track) },
                    onAddToPlaylist = {
                        trackMenu = false
                        addingTrack = library.firstOrNull { it.id == track.key }
                    },
                    onDownload = { format ->
                        val saved = library.firstOrNull { it.id == track.key }
                        if (saved != null) {
                            viewModel.downloadRecordAs(saved, format)
                        } else {
                            viewModel.downloadTrack(track, format)
                        }
                    },
                    onEdit = {
                        trackMenu = false
                        editingRecord = library.firstOrNull { it.id == track.key }
                    },
                    onRemoveDownload = {
                        trackMenu = false
                        library.firstOrNull { it.id == track.key }?.let(viewModel::delete)
                    },
                    onShowLyrics = {
                        trackMenu = false
                        viewModel.openLyrics(track)
                    },
                )
            }
        }

        addingTrack?.let { record ->
            AddToPlaylistSheet(
                playlists = playlists,
                onDismiss = { addingTrack = null },
                onPick = { playlist ->
                    viewModel.addToPlaylist(playlist.id, record)
                    addingTrack = null
                },
                onCreateNew = {
                    addingTrack = null
                    creatingPlaylistFor = record
                },
            )
        }

        creatingPlaylistFor?.let { record ->
            CreatePlaylistDialog(
                onDismiss = { creatingPlaylistFor = null },
                onCreate = { name ->
                    val id = viewModel.createPlaylist(name)
                    viewModel.addToPlaylist(id, record)
                    creatingPlaylistFor = null
                },
            )
        }

        editingRecord?.let { record ->
            TagEditorDialog(
                record = record,
                onDismiss = { editingRecord = null },
                onMessage = showMessage,
                onSaved = { editingRecord = null },
            )
        }

        // Drawn after the player so opening lyrics from a song menu is visible on top
        // of it; before, the player covered the lyrics entirely.
        LyricsHost(
            state = lyricsState,
            positionFlow = viewModel.position,
            currentKey = playback.current?.key,
            style = lyricsStyle,
            onClose = viewModel::closeLyrics,
            onFetch = { lyricsState.track?.let(viewModel::fetchLyrics) },
            onSave = viewModel::saveLyrics,
        )

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

        AnimatedVisibility(
            visible = showBrowser,
            enter = slideInVertically(
                animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
            ) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(180)),
        ) {
            BrowserScreen(
                onDismiss = { showBrowser = false },
                onSessionChanged = { viewModel.refreshSession() },
                onMessage = showMessage,
            )
        }

        BackHandler(enabled = showNowPlaying) { showNowPlaying = false }
        BackHandler(enabled = collectionTitle != null) { viewModel.closeCollection() }
        BackHandler(enabled = openPlaylist != null) { viewModel.closePlaylist() }
        BackHandler(enabled = showSettings) { showSettings = false }
        BackHandler(enabled = lyricsState.track != null) { viewModel.closeLyrics() }
        BackHandler(enabled = ranking != null) { ranking = null }
    }
}

@Composable
private fun PillNav(
    selected: PlayerTab,
    tabs: List<PlayerTab>,
    onSelect: (PlayerTab) -> Unit,
) {
    // With large system text the four labels no longer fit next to their icons; the
    // filled pill and the tinted icon still say which tab is open.
    val iconOnly = LocalDensity.current.fontScale > 1.2f
    GlassSurface(shape = CircleShape, strong = true) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEach { item ->
                val active = item == selected
                val background by animateColorAsState(
                    targetValue = if (active) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        Color.Transparent
                    },
                    // A spring rather than a fade makes the selected pill feel pressed
                    // into place, matching the raised icons and the mini player.
                    animationSpec = spring(
                        dampingRatio = 0.72f,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    label = "navItem",
                )
                val iconScale by animateFloatAsState(
                    targetValue = if (active) 1f else 0.9f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "navIcon",
                )
                val tint by animateColorAsState(
                    targetValue = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(240),
                    label = "navTint",
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
                        // An active tab shows its label, so the icon is decorative then.
                        contentDescription = if (active) null else stringResource(item.label),
                        tint = tint,
                        modifier = Modifier
                            .size(20.dp)
                            .scale(iconScale),
                    )
                    AnimatedVisibility(visible = active && !iconOnly) {
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
    positionFlow: StateFlow<Long>,
    durationMs: Long,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }

    val offsetY by animateFloatAsState(
        targetValue = if (dragging) dragY else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "miniOffsetY",
    )
    val offsetX by animateFloatAsState(
        targetValue = when {
            leaving -> -1400f
            dragging -> dragX
            else -> 0f
        },
        // Leaving is a flick rather than a settle, so it keeps its speed instead of
        // springing back to the edge it started from.
        animationSpec = if (leaving) {
            tween(220, easing = FastOutSlowInEasing)
        } else {
            spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
        },
        label = "miniOffsetX",
    )

    // Playback ends once the card is out of frame, so the gesture finishes its motion
    // instead of the player vanishing under the thumb.
    LaunchedEffect(leaving) {
        if (leaving) {
            delay(230)
            onDismiss()
        }
    }

    // If the swipe was declined (the track changed underneath it), the card must come
    // back for the next song instead of staying parked off-screen.
    LaunchedEffect(track.key) { leaving = false }

    GlassSurface(
        shape = RoundedCornerShape(20.dp),
        strong = true,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = offsetX
                translationY = offsetY
                // A little fade as it leaves, so the gesture reads before it lands.
                alpha = (1f - ((-offsetY).coerceIn(0f, 200f) / 660f)) *
                    (1f - ((-offsetX).coerceIn(0f, 700f) / 900f))
            }
            .clip(RoundedCornerShape(20.dp))
            .pointerInput(Unit) {
                // One gesture handler with an axis lock: up opens the player, sideways
                // swipes the card away and stops the song. Competing detectors would
                // fight over the first few pixels.
                var axis = DRAG_UNKNOWN
                detectDragGestures(
                    onDragStart = {
                        axis = DRAG_UNKNOWN
                        dragging = true
                        opened = false
                        dragY = 0f
                        dragX = 0f
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        if (axis == DRAG_UNKNOWN &&
                            (abs(delta.x) > 3f || abs(delta.y) > 3f)
                        ) {
                            axis = if (abs(delta.x) > abs(delta.y)) {
                                DRAG_SIDEWAYS
                            } else {
                                DRAG_VERTICAL
                            }
                        }
                        when (axis) {
                            DRAG_SIDEWAYS -> dragX = (dragX + delta.x).coerceIn(-1000f, 160f)
                            DRAG_VERTICAL -> {
                                dragY = (dragY + delta.y).coerceIn(-200f, 40f)
                                // Open as the finger crosses the line, not on release:
                                // waiting made the card spring back while the player
                                // started to rise.
                                if (!opened && dragY < -SWIPE_UP_TO_OPEN) {
                                    opened = true
                                    dragging = false
                                    dragY = 0f
                                    onOpen()
                                }
                            }
                        }
                    },
                    onDragEnd = {
                        if (axis == DRAG_SIDEWAYS && dragX < -SWIPE_LEFT_TO_DISMISS) {
                            leaving = true
                        }
                        dragging = false
                        dragY = 0f
                        dragX = 0f
                        opened = false
                    },
                    onDragCancel = {
                        dragging = false
                        dragY = 0f
                        dragX = 0f
                        opened = false
                    },
                )
            },
    ) {
        Column {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Opening is its own target: the play/pause button must never also fire
                // the card's click, and a tap on the artwork is the obvious way in.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpen)
                        .padding(vertical = 2.dp),
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
            MiniProgress(positionFlow = positionFlow, durationMs = durationMs)
        }
    }
}

/** The two-pixel progress line: the only part of the mini player that ticks. */
@Composable
private fun MiniProgress(positionFlow: StateFlow<Long>, durationMs: Long) {
    val positionMs by positionFlow.collectAsStateWithLifecycle()
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
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

@Composable
private fun NowPlayingScreen(
    state: PlaybackState,
    positionFlow: StateFlow<Long>,
    track: PlayableTrack,
    accentColors: List<Color>?,
    isFavorite: Boolean,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenLyrics: () -> Unit,
) {
    val dragY = remember { Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    // Opening plays the collapse in reverse: the cover starts on the mini player's tile
    // and grows into place while the backdrop and controls fade in.
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        intro.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 120.dp.toPx() }
    val lyricsThreshold = with(density) { 64.dp.toPx() }
    var lyricsTriggered by remember { mutableStateOf(false) }

    // Everything below is driven by the drag itself, so the player collapses the way it
    // would follow the thumb rather than waiting for the finger to lift.
    val configuration = LocalConfiguration.current
    val screenWidth = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() }
    val closeDistance = screenHeight * 0.36f
    val progress = maxOf(
        (dragY.value / (dismissThreshold * 1.6f)).coerceIn(0f, 1f),
        1f - intro.value,
    )
    val backdropAlpha = 1f - progress
    val contentAlpha = (1f - progress * 2.4f).coerceIn(0f, 1f)
    val artAlpha = (1f - ((progress - 0.72f) / 0.28f).coerceIn(0f, 1f))
    // Where the cover comes to rest: the mini player's 44dp tile at the bottom left.
    val artTargetX = with(density) { 42.dp.toPx() }
    val artTargetY = screenHeight - with(density) { 124.dp.toPx() }
    val artStartX = screenWidth / 2f
    val artStartY = screenHeight * 0.40f

    // The cover's own colours, with saturation and lightness clamped: an unclamped
    // gradient from a brown-ish cover is what made the old backdrop look muddy.
    val playerAccents = remember(accentColors) { accentColors?.map(::softenedAccent) }
    val playerAccent = playerAccents?.firstOrNull() ?: MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // A small follow-through keeps the sheet attached to the finger; the
                // real motion is the cover collapsing towards the mini player.
                translationY = dragY.value.coerceAtLeast(0f) * 0.12f
                val pull = (-dragY.value).coerceIn(0f, 360f) / 360f
                scaleX = 1f - pull * 0.05f
                scaleY = 1f - pull * 0.05f
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { lyricsTriggered = false },
                    onVerticalDrag = { change, delta ->
                        change.consume()
                        val next = (dragY.value + delta)
                            .coerceIn(-lyricsThreshold * 3f, closeDistance)
                        dragScope.launch { dragY.snapTo(next) }
                        // Open lyrics the moment the pull passes the line instead of
                        // waiting for the finger to lift; a short swipe now always works.
                        if (!lyricsTriggered && next < -lyricsThreshold) {
                            lyricsTriggered = true
                            dragScope.launch {
                                dragY.animateTo(0f, tween(160, easing = FastOutSlowInEasing))
                            }
                            onOpenLyrics()
                        }
                    },
                    onDragEnd = {
                        if (dragY.value > dismissThreshold) {
                            // Finish the collapse first; the invisible overlay then
                            // slides away underneath the mini player it has become.
                            dragScope.launch {
                                dragY.animateTo(
                                    closeDistance,
                                    tween(210, easing = FastOutSlowInEasing),
                                )
                                onClose()
                            }
                        } else {
                            dragScope.launch {
                                dragY.animateTo(
                                    0f,
                                    spring(
                                        dampingRatio = 0.82f,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                )
                            }
                        }
                        lyricsTriggered = false
                    },
                    onDragCancel = {
                        dragScope.launch {
                            dragY.animateTo(0f, spring(dampingRatio = 0.82f))
                        }
                        lyricsTriggered = false
                    },
                )
            },
    ) {
        // Immersive backdrop: the artwork itself, blurred, under a dark scrim. The
        // blur is a no-op below API 31, so the alpha keeps it readable there too.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = backdropAlpha }
                .background(
                    Brush.verticalGradient(
                        listOf(
                            lerp(playerAccent, Color.Black, 0.92f),
                            Color.Black,
                        )
                    )
                )
        )
        if (!track.artUrl.isNullOrBlank()) {
            AsyncImage(
                // Blurred in the background by [SoftenTransformation] instead of at draw
                // time; a 96px texture is all a full-screen soft backdrop needs.
                model = ImageRequest.Builder(context)
                    .data(track.artUrl)
                    .size(96, 96)
                    .transformations(SoftenTransformation(radius = 5, passes = 4))
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = backdropAlpha * 0.82f },
            )
        }
        // Slow colour waves drawn from the cover, under the scrim. Radial gradients are
        // soft on their own, so this needs no RenderEffect blur and costs two circles a
        // frame; Compose stops the clock as soon as the player is not on screen.
        AuroraBackdrop(
            colors = playerAccents,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = backdropAlpha },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = backdropAlpha }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = contentAlpha },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    // Tapping close collapses the cover into the mini player first,
                    // exactly like the drag, instead of sliding the whole sheet away.
                    dragScope.launch {
                        dragY.animateTo(closeDistance, tween(240, easing = FastOutSlowInEasing))
                        onClose()
                    }
                }) {
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
                IconButton(onClick = onOpenMenu) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.player_more),
                        tint = Color.White,
                    )
                }
            }

            Spacer(Modifier.weight(0.12f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .graphicsLayer {
                        scaleX = 1f - 0.86f * progress
                        scaleY = 1f - 0.86f * progress
                        translationX = (artTargetX - artStartX) * progress
                        translationY = (artTargetY - artStartY) * progress
                        alpha = artAlpha
                    },
                contentAlignment = Alignment.Center,
            ) {
                SpecialArtworkGlow(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            scaleX = 1.08f
                            scaleY = 1.08f
                        }
                )
                Crossfade(
                    targetState = track.artUrl,
                    animationSpec = tween(420),
                    label = "nowArt",
                ) { art ->
                    Artwork(
                        url = art,
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(28.dp),
                        iconSize = 72.dp,
                    )
                }
            }

            Spacer(Modifier.weight(0.08f))

            Text(
                text = track.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = contentAlpha },
            )
            Text(
                text = track.artist.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = contentAlpha },
            )

            Spacer(Modifier.height(16.dp))

            ProgressSection(
                positionFlow = positionFlow,
                durationMs = state.durationMs,
                accent = playerAccent,
                onSeek = onSeek,
                modifier = Modifier.graphicsLayer { alpha = contentAlpha },
            )

            Spacer(Modifier.weight(0.08f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = contentAlpha },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = onToggleShuffle,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (state.shuffle) {
                            Color.White.copy(alpha = 0.16f)
                        } else {
                            Color.Transparent
                        },
                    ),
                ) {
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
                    AnimatedContent(
                        targetState = state.playing,
                        transitionSpec = {
                            (fadeIn(tween(150)) + scaleIn(initialScale = 0.55f)) togetherWith
                                (fadeOut(tween(120)) + scaleOut(targetScale = 0.55f))
                        },
                        label = "nowPlay",
                    ) { isPlaying ->
                        Icon(
                            painter = painterResource(
                                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                            ),
                            contentDescription = stringResource(
                                if (isPlaying) R.string.player_pause else R.string.player_play
                            ),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
                IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_next),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(34.dp),
                    )
                }
                IconButton(
                    onClick = onCycleRepeat,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (state.repeatMode != RepeatMode.OFF) {
                            Color.White.copy(alpha = 0.16f)
                        } else {
                            Color.Transparent
                        },
                    ),
                ) {
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
                    .padding(top = 10.dp)
                    .graphicsLayer { alpha = contentAlpha },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToggleFavorite) {
                    val heartScale by animateFloatAsState(
                        targetValue = if (isFavorite) 1.14f else 1f,
                        animationSpec = spring(
                            dampingRatio = 0.44f,
                            stiffness = Spring.StiffnessMedium,
                        ),
                        label = "heartScale",
                    )
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
                        modifier = Modifier.scale(heartScale),
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
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .graphicsLayer { alpha = contentAlpha },
                )
            }

            Spacer(Modifier.weight(0.06f))
        }
    }
}

/**
 * Keeps the last lyrics state around while the sheet slides away, so closing it is a
 * motion rather than a cut. The screen reads its own data from [LyricsState], which is
 * why the last pair is cached here.
 */
/**
 * The seek bar lives in its own composable so the twice-a-second clock invalidates a
 * slider instead of the whole player. Re-reading it at the top of [NowPlayingScreen]
 * recomposed the blurred artwork backdrop on every tick, which is the most expensive
 * thing a mid-range phone can be asked to redraw.
 */
@Composable
private fun ProgressSection(
    positionFlow: StateFlow<Long>,
    durationMs: Long,
    accent: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = durationMs.coerceAtLeast(1L)
    val positionMs by positionFlow.collectAsStateWithLifecycle()
    var scrub by remember { mutableStateOf<Float?>(null) }
    val fraction = scrub ?: (positionMs.toFloat() / duration).coerceIn(0f, 1f)

    Column(modifier) {
        SimpleSeekBar(
            fraction = fraction,
            accent = accent,
            onScrub = { scrub = it },
            onCommit = { value ->
                onSeek((value * duration).toLong())
                scrub = null
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatClockMillis(
                    scrub?.let { (it * duration).toLong() } ?: positionMs
                ),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.65f),
            )
            Text(
                text = formatClockMillis(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.65f),
            )
        }
    }
}

/**
 * A seek bar that draws the played part as a gentle wave while audio is moving and
 * flattens when paused: the motion is the difference between "playing" and "paused"
 * without adding another icon to the row.
 */
@Composable
private fun SimpleSeekBar(
    fraction: Float,
    accent: Color,
    onScrub: (Float?) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pending by remember { mutableStateOf<Float?>(null) }
    var pressed by remember { mutableStateOf(false) }
    val knobScale by animateFloatAsState(
        targetValue = if (pressed) 1.35f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "seekKnob",
    )

    Canvas(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (size.width > 0) {
                        onCommit((offset.x / size.width).coerceIn(0f, 1f))
                    }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        pressed = true
                        val value = if (size.width > 0) {
                            (offset.x / size.width).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        pending = value
                        onScrub(value)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val value = (change.position.x / size.width).coerceIn(0f, 1f)
                        pending = value
                        onScrub(value)
                    },
                    onDragEnd = {
                        pressed = false
                        pending?.let(onCommit)
                        pending = null
                        onScrub(null)
                    },
                    onDragCancel = {
                        pressed = false
                        pending = null
                        onScrub(null)
                    },
                )
            }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
            },
    ) {
        val width = size.width
        val centre = size.height / 2f
        val thickness = 3.dp.toPx()
        drawLine(
            color = Color.White.copy(alpha = 0.18f),
            start = Offset(0f, centre),
            end = Offset(width, centre),
            strokeWidth = thickness,
            cap = StrokeCap.Round,
        )
        val end = width * fraction.coerceIn(0f, 1f)
        drawLine(
            color = accent,
            start = Offset(0f, centre),
            end = Offset(end.coerceAtLeast(thickness), centre),
            strokeWidth = thickness,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = Color.White,
            radius = 4.5.dp.toPx() * knobScale,
            center = Offset(end, centre),
        )
    }
}

/** Keeps artwork accents readable: saturated enough to tint, never neon or muddy. */
private fun softenedAccent(color: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[1] = hsl[1].coerceIn(0.30f, 0.55f)
    hsl[2] = hsl[2].coerceIn(0.42f, 0.62f)
    return Color(ColorUtils.HSLToColor(hsl))
}

/**
 * Two soft colour fields drifting against each other, tinted from the cover art and
 * falling back to the accent when there is no artwork.
 */
@Composable
private fun AuroraBackdrop(colors: List<Color>?, modifier: Modifier = Modifier) {
    val first = colors?.firstOrNull() ?: MaterialTheme.colorScheme.primary
    val second = colors?.getOrNull(1) ?: MaterialTheme.colorScheme.tertiary

    // Driven at ~30 fps by hand. An infinite transition requests a frame every vsync,
    // which is pure waste on two gradients that take half a minute to cross the screen.
    var drift by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        var last = 0L
        while (true) {
            val now = withFrameMillis { it }
            if (now - last < 32L) continue
            last = now
            val phase = ((now - start) % 52_000L) / 52_000f
            drift = if (phase <= 0.5f) phase * 2f else 2f - phase * 2f
        }
    }

    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val firstCenter = Offset(
            width * (0.20f + 0.24f * drift),
            height * (0.26f - 0.14f * drift),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(first.copy(alpha = 0.45f), Color.Transparent),
                center = firstCenter,
                radius = width * 0.9f,
            ),
            radius = width * 0.9f,
            center = firstCenter,
        )
        val secondCenter = Offset(
            width * (0.82f - 0.22f * drift),
            height * (0.64f + 0.18f * drift),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(second.copy(alpha = 0.38f), Color.Transparent),
                center = secondCenter,
                radius = width * 0.95f,
            ),
            radius = width * 0.95f,
            center = secondCenter,
        )
    }
}

@Composable
private fun LyricsHost(
    state: LyricsState,
    positionFlow: StateFlow<Long>,
    currentKey: String?,
    style: LyricsStyle,
    onClose: () -> Unit,
    onFetch: () -> Unit,
    onSave: (String) -> Unit,
) {
    var lastTrack by remember { mutableStateOf<PlayableTrack?>(null) }
    var lastState by remember { mutableStateOf<LyricsState?>(null) }
    val track = state.track

    LaunchedEffect(track, state) {
        if (track != null) {
            lastTrack = track
            lastState = state
        }
    }

    AnimatedVisibility(
        visible = track != null,
        enter = slideInVertically(
            animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
        ) { it } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
    ) {
        val shown = track ?: lastTrack
        val shownState = if (track != null) state else lastState
        if (shown != null && shownState != null) {
            LyricsSheet(
                track = shown,
                state = shownState,
                positionFlow = positionFlow,
                currentKey = currentKey,
                style = style,
                onClose = onClose,
                onFetch = onFetch,
                onSave = onSave,
            )
        }
    }
}

/**
 * The position subscription lives here, inside the visible branch, so a closed lyrics
 * sheet costs nothing even though playback keeps ticking.
 */
@Composable
private fun LyricsSheet(
    track: PlayableTrack,
    state: LyricsState,
    positionFlow: StateFlow<Long>,
    currentKey: String?,
    style: LyricsStyle,
    onClose: () -> Unit,
    onFetch: () -> Unit,
    onSave: (String) -> Unit,
) {
    val livePosition by positionFlow.collectAsStateWithLifecycle()
    LyricsScreen(
        track = track,
        state = state,
        positionMs = if (track.key == currentKey) livePosition else 0L,
        style = style,
        onClose = onClose,
        onFetch = onFetch,
        onSave = onSave,
    )
}

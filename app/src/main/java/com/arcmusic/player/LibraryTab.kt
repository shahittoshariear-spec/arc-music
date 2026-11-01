package com.arcmusic.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arcmusic.R
import com.arcmusic.data.AppTheme
import com.arcmusic.data.DownloadRecord
import com.arcmusic.ui.MainViewModel
import com.arcmusic.ui.TagEditorDialog
import kotlinx.coroutines.launch

enum class LibrarySort { TITLE, ARTIST, RECENT, PLAYED }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryTab(
    viewModel: MainViewModel,
    searchEnabled: Boolean,
    onGoSearch: () -> Unit,
    onMessage: (String) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val library by viewModel.items.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()

    val logo = when (theme) {
        AppTheme.CRIMSON -> R.drawable.ic_arc_logo_crimson
        AppTheme.OCEAN -> R.drawable.ic_arc_logo_ocean
        AppTheme.VIOLET -> R.drawable.ic_arc_logo_violet
        AppTheme.EMERALD -> R.drawable.ic_arc_logo
        AppTheme.SUNSET -> R.drawable.ic_arc_logo_sunset
        AppTheme.GRAPHITE -> R.drawable.ic_arc_logo_graphite
        AppTheme.P3R -> R.drawable.ic_arc_logo_p3r
        AppTheme.CELESTIAL -> R.drawable.ic_arc_logo_celestial
        AppTheme.CUSTOM -> R.drawable.ic_arc_logo_crimson
    }

    var sort by rememberSaveable { mutableStateOf(LibrarySort.TITLE) }
    val searchAtBottom by viewModel.searchButtonAtBottom.collectAsStateWithLifecycle()
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val selectionMode = selectedIds.isNotEmpty()

    // The bar is pinned above the letter list when the setting puts search at the top;
    // at the bottom it opens on demand from the pill. Turning search off removes both.
    val showSearchBar = searchEnabled && (!searchAtBottom || searchOpen)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(searchOpen) { if (searchOpen) focusRequester.requestFocus() }
    // Search can be switched off while its bar is open; do not leave a hidden filter on.
    LaunchedEffect(searchEnabled) {
        if (!searchEnabled) {
            searchOpen = false
            query = ""
        }
    }
    // Moving the button between top and bottom must not leave an invisible filter on.
    LaunchedEffect(searchAtBottom) {
        searchOpen = false
        query = ""
    }

    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }
    BackHandler(enabled = searchAtBottom && searchOpen) {
        searchOpen = false
        query = ""
    }
    var sortMenu by remember { mutableStateOf(false) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DownloadRecord?>(null) }
    var addingRecord by remember { mutableStateOf<DownloadRecord?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }

    val plays = remember(stats) { stats.tracks.associate { it.key to it.plays } }
    val visible = remember(library, favoritesOnly, favorites, sort, plays, query) {
        library
            .filter { !favoritesOnly || it.id in favorites }
            .filter { record ->
                query.isBlank() ||
                    record.title.contains(query, ignoreCase = true) ||
                    record.uploader?.contains(query, ignoreCase = true) == true
            }
            .sortedWith(
                when (sort) {
                    LibrarySort.TITLE -> compareBy { it.title.lowercase() }
                    LibrarySort.ARTIST -> compareBy<DownloadRecord> { it.uploader?.lowercase().orEmpty() }
                        .thenBy { it.title.lowercase() }
                    LibrarySort.RECENT -> compareByDescending { it.createdAt }
                    LibrarySort.PLAYED -> compareByDescending<DownloadRecord> { plays[it.id] ?: 0 }
                        .thenBy { it.title.lowercase() }
                }
            )
    }
    // Alphabetical order gets the Apple Music treatment: a letter before each group and
    // a draggable index down the right edge that jumps to a letter as it is passed.
    val sections = remember(visible, sort) {
        if (sort == LibrarySort.TITLE && visible.isNotEmpty()) {
            visible
                .groupBy { record ->
                    record.title.firstOrNull { it.isLetter() }?.uppercaseChar() ?: '#'
                }
                .entries
                .sortedWith(compareBy({ it.key == '#' }, { it.key }))
                .map { it.key to it.value }
        } else {
            null
        }
    }
    val headerIndices = remember(sections) {
        val map = mutableMapOf<Char, Int>()
        // The title row sits outside the list now, so a letter's songs start at zero.
        var cursor = 0
        sections?.forEach { (letter, songs) ->
            map[letter] = cursor
            cursor += 1 + songs.size
        }
        map
    }
    val positions = remember(visible) {
        visible.withIndex().associate { it.value.id to it.index }
    }
    // Playing from a row follows the order on screen, which puts "#" last.
    val playOrder = remember(sections, visible) { sections?.flatMap { it.second } ?: visible }
    val playPositions = remember(playOrder) {
        playOrder.withIndex().associate { it.value.id to it.index }
    }

    // A filter can hide songs that were selected before it was typed; the count must
    // describe what is on screen, not what used to be.
    LaunchedEffect(query) {
        if (selectedIds.isNotEmpty()) {
            val visibleIds = visible.mapTo(HashSet()) { it.id }
            selectedIds = selectedIds.filterTo(HashSet()) { it in visibleIds }
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                Box(contentAlignment = Alignment.Center) {
                    SpecialLogoHalo(theme)
                    Image(
                        painter = painterResource(logo),
                        contentDescription = null,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape),
                    )
                }
                Spacer(Modifier.size(10.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.player_library),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (library.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.player_library_count, library.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                IconButton(onClick = { showPlaylists = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_queue),
                        contentDescription = stringResource(R.string.player_playlists),
                    )
                }
                IconButton(onClick = { favoritesOnly = !favoritesOnly }) {
                    Icon(
                        painter = painterResource(
                            if (favoritesOnly) R.drawable.ic_favorite_filled else R.drawable.ic_favorite
                        ),
                        contentDescription = stringResource(R.string.player_favorites_only),
                        tint = if (favoritesOnly) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_sort),
                            contentDescription = stringResource(R.string.player_sort),
                        )
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortItem(R.string.player_sort_title, LibrarySort.TITLE, sort) {
                            sort = it
                            sortMenu = false
                        }
                        SortItem(R.string.player_sort_artist, LibrarySort.ARTIST, sort) {
                            sort = it
                            sortMenu = false
                        }
                        SortItem(R.string.player_sort_recent, LibrarySort.RECENT, sort) {
                            sort = it
                            sortMenu = false
                        }
                        SortItem(R.string.player_sort_played, LibrarySort.PLAYED, sort) {
                            sort = it
                            sortMenu = false
                        }
                    }
                }
            }

        if (showSearchBar && !searchAtBottom) {
            LibrarySearchBar(
                query = query,
                onQuery = { query = it },
                focusRequester = focusRequester,
                onClose = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 36.dp,
                top = 6.dp,
                // Room for the floating search pill, so the last song is not covered.
                bottom = if (searchEnabled && searchAtBottom && !searchOpen) 96.dp else 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {

        if (visible.isEmpty()) {
            item("empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (query.isNotBlank()) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(46.dp),
                        )
                        Text(
                            text = stringResource(R.string.player_search_no_matches, query),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Icon(
                        painter = painterResource(
                            if (library.isEmpty()) R.drawable.ic_library else R.drawable.ic_favorite
                        ),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(52.dp),
                    )
                    Text(
                        text = stringResource(
                            if (library.isEmpty()) {
                                R.string.player_library_empty
                            } else {
                                R.string.player_no_favorites
                            }
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            if (library.isEmpty()) {
                                R.string.player_library_empty_help
                            } else {
                                R.string.player_no_favorites_help
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (library.isEmpty() && searchEnabled) {
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = onGoSearch) {
                            Text(stringResource(R.string.player_tab_search))
                        }
                    }
                    }
                }
            }
        } else if (sections != null) {
            sections.forEach { (letter, songs) ->
                item(key = "letter:$letter") {
                    LetterHeader(letter)
                }
                itemsIndexed(songs, key = { _, record -> record.id }) { _, record ->
                    SongRow(
                        record = record,
                        current = playback.current?.key == record.id,
                        playing = playback.playing,
                        plays = plays[record.id] ?: 0,
                        favorite = record.id in favorites,
                        onPlay = {
                            if (selectionMode) {
                                selectedIds = if (record.id in selectedIds) {
                                    selectedIds - record.id
                                } else {
                                    selectedIds + record.id
                                }
                            } else if (playback.current?.key == record.id) {
                                onOpenPlayer()
                            } else {
                                viewModel.playRecords(playOrder, playPositions[record.id] ?: 0)
                            }
                        },
                        selected = record.id in selectedIds,
                        selectionMode = selectionMode,
                        onLongPress = { selectedIds = selectedIds + record.id },
                        onToggleFavorite = { viewModel.toggleFavorite(record.id) },
                        onPlayNext = { viewModel.playRecordNext(record) },
                        onAddToQueue = { viewModel.addRecordToQueue(record) },
                        onAddToPlaylist = { addingRecord = record },
                        onLyrics = { viewModel.openLyrics(record.toPlayable()) },
                        onEdit = { editing = record },
                        onDelete = { viewModel.delete(record) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        } else {
            itemsIndexed(visible, key = { _, record -> record.id }) { index, record ->
                SongRow(
                    record = record,
                    current = playback.current?.key == record.id,
                    playing = playback.playing,
                    plays = plays[record.id] ?: 0,
                    favorite = record.id in favorites,
                    onPlay = {
                        if (selectionMode) {
                            selectedIds = if (record.id in selectedIds) {
                                selectedIds - record.id
                            } else {
                                selectedIds + record.id
                            }
                        } else if (playback.current?.key == record.id) {
                            onOpenPlayer()
                        } else {
                            viewModel.playRecords(playOrder, index)
                        }
                    },
                    selected = record.id in selectedIds,
                    selectionMode = selectionMode,
                    onLongPress = { selectedIds = selectedIds + record.id },
                    onToggleFavorite = { viewModel.toggleFavorite(record.id) },
                    onPlayNext = { viewModel.playRecordNext(record) },
                    onAddToQueue = { viewModel.addRecordToQueue(record) },
                    onAddToPlaylist = { addingRecord = record },
                    onLyrics = { viewModel.openLyrics(record.toPlayable()) },
                    onEdit = { editing = record },
                    onDelete = { viewModel.delete(record) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

        if (sections != null && sections.size > 2) {
            AlphabetIndex(
                letters = sections.map { it.first },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(top = 4.dp, bottom = 12.dp)
                    .width(26.dp),
            ) { letter ->
                headerIndices[letter]?.let { header ->
                    scope.launch { listState.animateScrollToItem(header) }
                }
            }
        }
        }
    }

        // Bottom search: a wide bar above the keyboard that can be swiped away.
        AnimatedVisibility(
            visible = searchAtBottom && searchOpen,
            enter = slideInVertically(tween(220)) { it } + fadeIn(tween(140)),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(120)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .zIndex(3f),
        ) {
            BottomSearchBar(
                query = query,
                onQuery = { query = it },
                focusRequester = focusRequester,
                onCancel = {
                    searchOpen = false
                    query = ""
                },
            )
        }

        // Selection controls float over the list instead of living at its top, so they
        // stay reachable however far down the user has scrolled.
        AnimatedVisibility(
            visible = selectionMode,
            enter = slideInVertically(tween(200)) { -it } + fadeIn(tween(140)),
            exit = slideOutVertically(tween(180)) { -it } + fadeOut(tween(120)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .zIndex(3f),
        ) {
            SelectionToolbar(
                count = selectedIds.size,
                allSelected = visible.isNotEmpty() && visible.all { it.id in selectedIds },
                onClose = { selectedIds = emptySet() },
                onSelectAll = {
                    selectedIds = if (visible.isNotEmpty() && selectedIds.size == visible.size) {
                        emptySet()
                    } else {
                        visible.map { it.id }.toSet()
                    }
                },
                onAutoTag = {
                    viewModel.autoTagRecords(library.filter { it.id in selectedIds })
                    selectedIds = emptySet()
                },
                onDelete = { confirmDelete = true },
            )
        }

        AnimatedVisibility(
            visible = searchEnabled && !selectionMode && searchAtBottom && !searchOpen,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(120)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 44.dp, vertical = 16.dp)
                .zIndex(2f),
        ) {
            GlassSurface(
                shape = CircleShape,
                strong = true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .pressable(onClick = { searchOpen = true })
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.player_search_button),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    editing?.let { record ->
        TagEditorDialog(
            record = record,
            onDismiss = { editing = null },
            onMessage = onMessage,
            onSaved = { editing = null },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(stringResource(R.string.player_delete_selected, selectedIds.size))
            },
            text = { Text(stringResource(R.string.player_delete_selected_help)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = selectedIds
                        selectedIds = emptySet()
                        confirmDelete = false
                        viewModel.deleteAll(records = library.filter { it.id in ids })
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showPlaylists) {
        PlaylistsSheet(
            playlists = playlists,
            onDismiss = { showPlaylists = false },
            onOpen = { playlist ->
                showPlaylists = false
                viewModel.openPlaylist(playlist.id)
            },
            onCreateNew = {
                showPlaylists = false
                showCreatePlaylist = true
            },
        )
    }

    if (addingRecord != null) {
        AddToPlaylistSheet(
            playlists = playlists,
            onDismiss = { addingRecord = null },
            onPick = { playlist ->
                addingRecord?.let { viewModel.addToPlaylist(playlist.id, it) }
                addingRecord = null
            },
            onCreateNew = {
                showCreatePlaylist = true
            },
        )
    }

    if (showCreatePlaylist) {
        CreatePlaylistDialog(
            onDismiss = {
                showCreatePlaylist = false
                addingRecord = null
            },
            onCreate = { name ->
                val id = viewModel.createPlaylist(name)
                addingRecord?.let { viewModel.addToPlaylist(id, it) }
                addingRecord = null
                showCreatePlaylist = false
            },
        )
    }
}

@Composable
private fun SortItem(
    label: Int,
    value: LibrarySort,
    current: LibrarySort,
    onPick: (LibrarySort) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        trailingIcon = if (value == current) {
            { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
        } else {
            null
        },
        onClick = { onPick(value) },
    )
}

@Composable
private fun LibrarySearchBar(
    query: String,
    onQuery: (String) -> Unit,
    focusRequester: FocusRequester,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(stringResource(R.string.player_search_in_library)) },
        leadingIcon = {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null)
        },
        trailingIcon = {
            when {
                query.isNotEmpty() -> IconButton(onClick = { onQuery("") }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.close),
                    )
                }
                onClose != null -> IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
    )
}

/**
 * The bottom search field: full width, rises with the keyboard, and swipes sideways
 * to leave without touching the keyboard's back key.
 */
@Composable
private fun BottomSearchBar(
    query: String,
    onQuery: (String) -> Unit,
    focusRequester: FocusRequester,
    onCancel: () -> Unit,
) {
    var dragX by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = dragX
                alpha = 1f - ((-dragX).coerceIn(0f, 260f) / 320f)
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        dragX = (dragX + delta).coerceAtMost(0f)
                    },
                    onDragEnd = {
                        if (dragX < -120f) onCancel() else dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                )
            },
    ) {
        LibrarySearchBar(
            query = query,
            onQuery = onQuery,
            focusRequester = focusRequester,
            onClose = onCancel,
        )
    }
}

@Composable
private fun SelectionToolbar(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onAutoTag: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassSurface(
        shape = RoundedCornerShape(18.dp),
        strong = true,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.cancel),
                )
            }
            Text(
                text = stringResource(R.string.player_selected_count, count),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSelectAll) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = stringResource(R.string.player_select_all),
                    tint = if (allSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onAutoTag) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.tag_autotag),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SongRow(
    record: DownloadRecord,
    current: Boolean,
    playing: Boolean,
    plays: Int,
    favorite: Boolean,
    onPlay: () -> Unit,
    selected: Boolean,
    selectionMode: Boolean,
    onLongPress: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onLyrics: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)

    // The highlight eases in and out instead of snapping, which is what makes a long
    // press read as a selection rather than a glitch.
    val container by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            current -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else -> glassRowColor()
        },
        animationSpec = tween(220),
        label = "rowContainer",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape),
    ) {
    Surface(
        shape = shape,
        color = container,
        border = BorderStroke(
            1.dp,
            if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                Color.White.copy(alpha = 0.07f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = onPlay, onLongClick = onLongPress, showRipple = false),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Artwork(record.thumbnailUrl, 54.dp, RoundedCornerShape(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (current) {
                        EqualizerBars(active = playing)
                    }
                    Text(
                        text = record.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(
                    text = listOfNotNull(
                        record.uploader?.takeIf { it.isNotBlank() },
                        // The slot stays for every song so the rows line up; a time that
                        // is not known yet reads as dashes instead of vanishing.
                        formatClock(record.durationSeconds).takeIf { it != "0:00" } ?: "--:--",
                        plays.takeIf { it > 0 }?.let {
                            stringResource(R.string.player_plays, it)
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .then(
                            if (selected) {
                                Modifier.background(MaterialTheme.colorScheme.primary)
                            } else {
                                Modifier.border(
                                    width = 1.5.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            } else {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.more),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(R.drawable.ic_queue, R.string.player_play_next) {
                        menu = false
                        onPlayNext()
                    }
                    MenuItem(R.drawable.ic_queue, R.string.player_add_queue) {
                        menu = false
                        onAddToQueue()
                    }
                    MenuItem(R.drawable.ic_playlist_add, R.string.player_add_to_playlist) {
                        menu = false
                        onAddToPlaylist()
                    }
                    MenuItem(
                        icon = if (favorite) {
                            R.drawable.ic_favorite_filled
                        } else {
                            R.drawable.ic_favorite
                        },
                        label = if (favorite) R.string.player_unfavorite else R.string.player_favorite,
                    ) {
                        menu = false
                        onToggleFavorite()
                    }
                    MenuItem(R.drawable.ic_lyrics, R.string.player_lyrics) {
                        menu = false
                        onLyrics()
                    }
                    MenuItem(R.drawable.ic_edit, R.string.tag_edit) {
                        menu = false
                        onEdit()
                    }
                    MenuItem(R.drawable.ic_delete, R.string.delete) {
                        menu = false
                        onDelete()
                    }
                }
            }
            }
        }
    }
    }
}

@Composable
private fun MenuItem(icon: Int, label: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
        onClick = onClick,
    )
}

@Composable
private fun LetterHeader(letter: Char) {
    // Plain text with no background: a sticky header's opaque band was reading as a
    // dark rule across the app's gradient.
    Text(
        text = letter.uppercaseChar().toString(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, top = 12.dp, bottom = 2.dp),
    )
}

/**
 * The A–Z strip. Dragging anywhere on it jumps to that letter and shows a floating
 * copy of the letter under the thumb, the way the system music apps do it.
 */
@Composable
private fun AlphabetIndex(
    letters: List<Char>,
    modifier: Modifier = Modifier,
    onSelect: (Char) -> Unit,
) {
    var active by remember { mutableStateOf<Char?>(null) }

    Box(
        modifier = modifier.pointerInput(letters) {
            fun letterAt(y: Float): Char {
                val ratio = (y / size.height.coerceAtLeast(1)).coerceIn(0f, 0.9999f)
                return letters[(ratio * letters.size).toInt().coerceIn(0, letters.lastIndex)]
            }

            detectVerticalDragGestures(
                onDragStart = { offset ->
                    val letter = letterAt(offset.y)
                    active = letter
                    onSelect(letter)
                },
                onVerticalDrag = { change, _ ->
                    change.consume()
                    val letter = letterAt(change.position.y)
                    if (letter != active) {
                        active = letter
                        onSelect(letter)
                    }
                },
                onDragEnd = { active = null },
                onDragCancel = { active = null },
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            letters.forEach { letter ->
                val selected = letter == active
                val scale by animateFloatAsState(
                    targetValue = if (selected) 1.7f else 1f,
                    animationSpec = spring(
                        dampingRatio = 0.45f,
                        stiffness = Spring.StiffnessMedium,
                    ),
                    label = "indexScale",
                )
                Text(
                    text = letter.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .padding(vertical = 1.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                )
            }
        }

        active?.let { letter ->
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-34).dp)
                    .size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = letter.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

private fun DownloadRecord.toPlayable() = PlayableTrack(
    key = id,
    title = title,
    artist = uploader,
    artUrl = thumbnailUrl,
    source = uri,
    isLocal = true,
    durationMs = durationSeconds * 1000L,
)

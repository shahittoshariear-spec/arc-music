package com.arcmusic.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arcmusic.R
import com.arcmusic.data.DownloadRecord
import com.arcmusic.data.LocalPlaylist
import com.arcmusic.ui.MainViewModel
import com.arcmusic.ui.TagEditorDialog

@Composable
fun LibraryTab(
    viewModel: MainViewModel,
    onGoSearch: () -> Unit,
    onOpenBrowser: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val collectionsLoading by viewModel.collectionsLoading.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<DownloadRecord?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    var pendingAdd by remember { mutableStateOf<DownloadRecord?>(null) }
    val sorted = remember(items) { items.sortedBy { it.title.lowercase() } }
    val visible = remember(sorted, favoritesOnly, favorites) {
        if (favoritesOnly) sorted.filter { it.id in favorites } else sorted
    }
    val totalBytes = remember(items) { items.sumOf { it.sizeBytes } }

    LaunchedEffect(signedIn) {
        if (signedIn) viewModel.loadCollections()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("header") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.player_library),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (items.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.player_library_count,
                            items.size,
                            formatBytes(totalBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item("filters") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !favoritesOnly,
                    onClick = { favoritesOnly = false },
                    label = { Text(stringResource(R.string.player_all)) },
                )
                FilterChip(
                    selected = favoritesOnly,
                    onClick = { favoritesOnly = true },
                    label = { Text(stringResource(R.string.player_favorites)) },
                )
            }
        }

        item("downloads") {
            val active = queue.count { it.isActive }
            YouTubeRow(
                icon = R.drawable.ic_download,
                title = if (active > 0) {
                    stringResource(R.string.player_downloads_active, active)
                } else {
                    stringResource(R.string.player_downloads)
                },
                onClick = { showDownloads = true },
            )
        }

        item("playlists-header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.player_playlists),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showCreatePlaylist = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.player_new_playlist))
                }
            }
        }

        if (playlists.isNotEmpty()) {
            item("playlists") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    playlists.forEach { playlist ->
                        val records = playlist.trackIds.mapNotNull { id ->
                            items.firstOrNull { it.id == id }
                        }
                        PlaylistCard(
                            playlist = playlist,
                            records = records,
                            onClick = { viewModel.openPlaylist(playlist.id) },
                        )
                    }
                }
            }
        }

        if (!signedIn) {
            item("signin-card") {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_account),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.player_sign_in_cta),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = stringResource(R.string.player_sign_in_help),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        TextButton(onClick = onOpenBrowser) {
                            Text(stringResource(R.string.browse_sign_in))
                        }
                    }
                }
            }
        } else {
            item("yt-header") {
                Text(
                    text = stringResource(R.string.player_your_youtube),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            item("yt-feeds") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    YouTubeRow(
                        icon = R.drawable.ic_note,
                        title = stringResource(R.string.player_liked),
                        onClick = viewModel::openLikedVideos,
                    )
                    YouTubeRow(
                        icon = R.drawable.ic_refresh,
                        title = stringResource(R.string.player_watch_later),
                        onClick = viewModel::openWatchLater,
                    )
                }
            }
            if (collectionsLoading) {
                item("yt-loading") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            items(collections, key = { it.url }) { collection ->
                YouTubeRow(
                    icon = R.drawable.ic_playlist_add,
                    title = collection.title,
                    onClick = { viewModel.openCollection(collection.title, collection.url) },
                )
            }
        }

        if (visible.size > 2) {
            item("recent") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.player_recent),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(
                            visible.sortedByDescending { it.createdAt }.take(12),
                            key = { "recent-" + it.id },
                        ) { record ->
                            val index = visible.indexOfFirst { it.id == record.id }
                            Column(
                                modifier = Modifier
                                    .width(124.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { viewModel.playRecords(visible, index.coerceAtLeast(0)) },
                            ) {
                                Artwork(record.thumbnailUrl, 124.dp, RoundedCornerShape(12.dp))
                                Text(
                                    text = record.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                                Text(
                                    text = record.uploader.orEmpty(),
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

        if (visible.isEmpty()) {
            item("empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        painter = painterResource(
                            if (items.isEmpty()) R.drawable.ic_library else R.drawable.ic_favorite
                        ),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(52.dp),
                    )
                    Text(
                        text = stringResource(
                            if (items.isEmpty()) {
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
                            if (items.isEmpty()) {
                                R.string.player_library_empty_help
                            } else {
                                R.string.player_no_favorites_help
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (items.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = onGoSearch) {
                            Text(stringResource(R.string.player_tab_search))
                        }
                    }
                }
            }
        } else {
            itemsIndexed(visible, key = { _, record -> record.id }) { index, record ->
                LibraryRow(
                    record = record,
                    isCurrent = playback.current?.key == record.id,
                    playing = playback.current?.key == record.id && playback.playing,
                    isFavorite = record.id in favorites,
                    onPlay = { viewModel.playRecords(visible, index) },
                    onPlayNext = { viewModel.playRecordNext(record) },
                    onAddToQueue = { viewModel.addRecordToQueue(record) },
                    onToggleFavorite = { viewModel.toggleFavorite(record.id) },
                    onAddToPlaylist = {
                        pendingAdd = record
                        showAddSheet = true
                    },
                    onEdit = { editing = record },
                    onDelete = { viewModel.delete(record) },
                    modifier = Modifier.animateItem(),
                )
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

    if (showDownloads) {
        DownloadsSheet(
            queue = queue,
            onDismiss = { showDownloads = false },
            onCancel = viewModel::cancelItem,
            onRetry = viewModel::retry,
            onClearFinished = viewModel::clearFinished,
        )
    }

    if (showAddSheet) {
        AddToPlaylistSheet(
            playlists = playlists,
            onDismiss = {
                showAddSheet = false
                pendingAdd = null
            },
            onPick = { playlist ->
                pendingAdd?.let { viewModel.addToPlaylist(playlist.id, it) }
                showAddSheet = false
                pendingAdd = null
            },
            onCreateNew = {
                showAddSheet = false
                showCreatePlaylist = true
            },
        )
    }

    if (showCreatePlaylist) {
        CreatePlaylistDialog(
            onDismiss = {
                showCreatePlaylist = false
                pendingAdd = null
            },
            onCreate = { name ->
                val id = viewModel.createPlaylist(name)
                pendingAdd?.let { viewModel.addToPlaylist(id, it) }
                pendingAdd = null
                showCreatePlaylist = false
            },
        )
    }
}

@Composable
private fun PlaylistCard(
    playlist: LocalPlaylist,
    records: List<DownloadRecord>,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .pressable(onClick = onClick),
    ) {
        PlaylistArtwork(
            urls = records.take(4).map { it.thumbnailUrl },
            size = 150.dp,
        )
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = stringResource(R.string.player_track_count, records.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun YouTubeRow(icon: Int, title: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .pressable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(-90f),
            )
        }
    }
}

@Composable
private fun LibraryRow(
    record: DownloadRecord,
    isCurrent: Boolean,
    playing: Boolean,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

    Surface(
        shape = shape,
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = modifier
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
                Artwork(record.thumbnailUrl, 54.dp, RoundedCornerShape(12.dp))
                if (playing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pause),
                            contentDescription = stringResource(R.string.player_pause),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        record.uploader?.takeIf { it.isNotBlank() },
                        formatClock(record.durationSeconds).takeIf { it != "0:00" },
                        formatBytes(record.sizeBytes).takeIf { it.isNotEmpty() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onToggleFavorite) {
                Icon(
                    painter = painterResource(
                        if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite
                    ),
                    contentDescription = null,
                    tint = if (isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }

            Text(
                text = record.format.shortName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )

            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more),
                        contentDescription = stringResource(R.string.more),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.player_play_next)) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_queue), contentDescription = null)
                        },
                        onClick = {
                            menu = false
                            onPlayNext()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.player_add_queue)) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_queue), contentDescription = null)
                        },
                        onClick = {
                            menu = false
                            onAddToQueue()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.player_add_to_playlist)) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_playlist_add), contentDescription = null)
                        },
                        onClick = {
                            menu = false
                            onAddToPlaylist()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tag_edit)) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_edit), contentDescription = null)
                        },
                        onClick = {
                            menu = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                        },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

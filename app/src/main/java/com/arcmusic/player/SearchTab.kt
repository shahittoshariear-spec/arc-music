package com.arcmusic.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arcmusic.R
import com.arcmusic.data.AudioFormat
import com.arcmusic.downloader.MediaInfo
import com.arcmusic.ui.MainViewModel
import com.arcmusic.ui.SearchMode

@Composable
fun SearchTab(
    viewModel: MainViewModel,
    onOpenBrowser: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val error by viewModel.searchError.collectAsStateWithLifecycle()
    val searchMode by viewModel.searchMode.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val library by viewModel.items.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val preparing by viewModel.preparingTrack.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val autoFormat by viewModel.autoFormat.collectAsStateWithLifecycle()
    val defaultFormat by viewModel.format.collectAsStateWithLifecycle()

    val downloadOptions = remember(autoFormat, defaultFormat) {
        if (autoFormat) {
            AudioFormat.entries.filter { it != defaultFormat }
        } else {
            AudioFormat.entries
        }
    }

    var pendingFormat by remember { mutableStateOf<MediaInfo?>(null) }
    var selected by remember { mutableStateOf<Map<String, MediaInfo>>(emptyMap()) }
    val selectionMode = selected.isNotEmpty()

    // A selection belongs to the list it was made in. Switching between songs and
    // playlists used to leave the toolbar counting rows that were no longer on screen.
    LaunchedEffect(searchMode) { selected = emptyMap() }
    val focus = LocalFocusManager.current

    BackHandler(enabled = selectionMode) { selected = emptyMap() }

    val recentPlays = remember(stats) { stats.recentlyPlayed(8) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.player_selected_count,
                                selected.size,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.player_download_selected_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            val chosen = selected.values.toList()
                            selected = emptyMap()
                            chosen.forEach { info ->
                                viewModel.downloadSearchResult(info, defaultFormat)
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = stringResource(R.string.player_download_selected),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { selected = emptyMap() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.player_tab_search),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.player_search_online_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item("field") {
            TextField(
                value = query,
                onValueChange = viewModel::onSearchQueryChange,
                placeholder = { Text(stringResource(R.string.player_search_placeholder)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focus.clearFocus()
                        viewModel.submitSearch()
                    }
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item("toggle") {
            SearchModeToggle(mode = searchMode, onChange = viewModel::setSearchMode)
        }

        // An empty box is the home screen: what was searched for, then what was played.
        if (query.isBlank() && searchMode != SearchMode.RECOMMENDATIONS) {
            if (!signedIn) {
                item("signin") { SignInBanner(onOpenBrowser) }
            }

            if (recentSearches.isNotEmpty()) {
                item("recent-searches-header") {
                    RecentHeader(
                        title = stringResource(R.string.player_recent_searches),
                        onClear = viewModel::clearRecentSearches,
                    )
                }
                items(recentSearches, key = { "search:$it" }) { phrase ->
                    RecentSearchRow(
                        phrase = phrase,
                        onClick = {
                            viewModel.onRecentSearchClick(phrase)
                            viewModel.submitSearch()
                        },
                        onRemove = { viewModel.forgetSearch(phrase) },
                    )
                }
            }

            if (recentPlays.isNotEmpty()) {
                item("recent-plays-header") {
                    RecentHeader(
                        title = stringResource(R.string.player_recently_played),
                        onClear = null,
                    )
                }
                items(recentPlays, key = { "play:${it.key}" }) { stat ->
                    val record = library.firstOrNull { it.id == stat.key }
                    RecentPlayRow(
                        title = stat.title.ifBlank { stat.key },
                        artist = stat.artist,
                        artUrl = stat.artUrl,
                        plays = stat.plays,
                        onClick = {
                            if (record != null) {
                                if (playback.current?.key == record.id) {
                                    onOpenPlayer()
                                } else {
                                    viewModel.playRecords(listOf(record), 0)
                                }
                            } else {
                                viewModel.onSearchQueryChange(stat.title)
                                viewModel.submitSearch()
                            }
                        },
                    )
                }
            }

            if (recentSearches.isEmpty() && recentPlays.isEmpty()) {
                item("recent-empty") {
                    Text(
                        text = stringResource(R.string.player_recent_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        if (searching) {
            item("loading") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.browse_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        error?.let { message ->
            item("error") {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (results.isNotEmpty()) {
                item("results-label") {
                    SectionLabel(
                        if (searchMode == SearchMode.PLAYLISTS) {
                            stringResource(R.string.search_mode_playlists)
                        } else if (searchMode == SearchMode.RECOMMENDATIONS) {
                            stringResource(R.string.search_results_recommendations)
                        } else {
                            "YouTube"
                        }
                    )
                }
                if (searchMode == SearchMode.PLAYLISTS) {
                    items(results, key = { it.url }) { info ->
                        PlaylistResultRow(
                            info = info,
                            onClick = { viewModel.openCollection(info.title, info.url) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                } else {
                    items(results, key = { it.url }) { info ->
                        val saved = library.any { it.sourceUrl == info.url }
                        SearchRow(
                            info = info,
                            visual = downloadVisualFor(info, queue, library),
                            playing = playback.current?.key == info.url && playback.playing,
                            preparing = preparing == info.url,
                            saved = saved,
                            favorite = info.url in favorites,
                            onPlay = {
                                if (selectionMode) {
                                    selected = if (info.url in selected) {
                                        selected - info.url
                                    } else {
                                        selected + (info.url to info)
                                    }
                                } else if (playback.current?.key == info.url) {
                                    onOpenPlayer()
                                } else {
                                    viewModel.playSearchResult(info)
                                }
                            },
                            selected = info.url in selected,
                            selectionMode = selectionMode,
                            onLongPress = { selected = selected + (info.url to info) },
                            downloadOptions = downloadOptions,
                            onDownloadFormat = { format ->
                                viewModel.downloadSearchResult(info, format)
                            },
                            onDownload = {
                                if (autoFormat) {
                                    viewModel.downloadSearchResult(info, defaultFormat)
                                } else {
                                    pendingFormat = info
                                }
                            },
                            onPlayNext = { viewModel.playSearchResultNext(info) },
                            onAddToQueue = { viewModel.addSearchResultToQueue(info) },
                            onRemoveDownload = { viewModel.removeDownloadFor(info.url) },
                            onToggleFavorite = { viewModel.toggleFavorite(info.url) },
                            onLyrics = { viewModel.openLyrics(info.toPlayable()) },
                            modifier = Modifier.animateItem(),
                        )
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
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerLowest
        } else {
            Color.Transparent
        },
        label = "segment",
    )
    Box(
        modifier = Modifier
            .shadow(
                elevation = if (selected) 3.dp else 0.dp,
                shape = CircleShape,
                clip = false,
            )
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Songs or playlists: the search results below follow this choice. */
@Composable
private fun SearchModeToggle(mode: SearchMode, onChange: (SearchMode) -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Segment(
                label = stringResource(R.string.search_mode_songs),
                selected = mode == SearchMode.SONGS,
            ) { onChange(SearchMode.SONGS) }
            Segment(
                label = stringResource(R.string.search_mode_playlists),
                selected = mode == SearchMode.PLAYLISTS,
            ) { onChange(SearchMode.PLAYLISTS) }
            Segment(
                label = stringResource(R.string.search_mode_recommendations),
                selected = mode == SearchMode.RECOMMENDATIONS,
            ) { onChange(SearchMode.RECOMMENDATIONS) }
        }
    }
}

/** One playlist in the results. Tapping it opens the songs it contains. */
@Composable
private fun PlaylistResultRow(
    info: MediaInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape),
    ) {
        Surface(
            shape = shape,
            color = glassRowColor(),
            modifier = Modifier
                .fillMaxWidth()
                .pressable(onClick = onClick, showRipple = false),
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Artwork(info.thumbnailUrl, 54.dp, RoundedCornerShape(12.dp))
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
                        text = info.uploader.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
}

@Composable
private fun SearchRow(
    info: MediaInfo,
    visual: DownloadVisual,
    playing: Boolean,
    preparing: Boolean,
    saved: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    selected: Boolean,
    selectionMode: Boolean,
    onLongPress: () -> Unit,
    onDownload: () -> Unit,
    downloadOptions: List<AudioFormat>,
    onDownloadFormat: (AudioFormat) -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onRemoveDownload: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape),
    ) {
    Surface(
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            glassRowColor()
        },
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
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Artwork(info.thumbnailUrl, 54.dp, RoundedCornerShape(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = info.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (playing || preparing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle(info.uploader, info.durationSeconds),
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
            if (preparing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                DownloadDial(state = visual, onClick = onDownload)
            }

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
                    MenuItem(
                        icon = if (favorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite,
                        label = if (favorite) R.string.player_unfavorite else R.string.player_favorite,
                    ) {
                        menu = false
                        onToggleFavorite()
                    }
                    if (saved) {
                        MenuItem(R.drawable.ic_delete, R.string.player_remove_download) {
                            menu = false
                            onRemoveDownload()
                        }
                    } else {
                        MenuItem(R.drawable.ic_download, R.string.browse_download) {
                            menu = false
                            onDownload()
                        }
                        downloadOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            R.string.player_download_as_format,
                                            stringResource(option.labelRes),
                                        )
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_download),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    menu = false
                                    onDownloadFormat(option)
                                },
                            )
                        }
                    }
                    MenuItem(R.drawable.ic_lyrics, R.string.player_lyrics) {
                        menu = false
                        onLyrics()
                    }
                }
            }
            }
        }
    }
    }
}

@Composable
private fun SignInBanner(onOpenBrowser: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_account),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.player_search_sign_in),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenBrowser) {
                Text(stringResource(R.string.browse_sign_in))
            }
        }
    }
}

@Composable
private fun RecentHeader(title: String, onClear: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onClear != null) {
            TextButton(onClick = onClear) {
                Text(
                    text = stringResource(R.string.player_recent_clear),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun RecentSearchRow(phrase: String, onClick: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = phrase,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.close),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentPlayRow(
    title: String,
    artist: String?,
    artUrl: String?,
    plays: Int,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .pressable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(artUrl, 46.dp, RoundedCornerShape(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = plays.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun MenuItem(icon: Int, label: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
        onClick = onClick,
    )
}

private fun MediaInfo.toPlayable() = PlayableTrack(
    key = url,
    title = title,
    artist = uploader,
    artUrl = thumbnailUrl,
    source = url,
    isLocal = false,
    durationMs = durationSeconds * 1000L,
)

private fun subtitle(artist: String?, seconds: Long): String {
    val clock = formatClock(seconds)
    return listOfNotNull(
        artist?.takeIf { it.isNotBlank() },
        clock.takeIf { it != "0:00" },
    ).joinToString(" · ")
}

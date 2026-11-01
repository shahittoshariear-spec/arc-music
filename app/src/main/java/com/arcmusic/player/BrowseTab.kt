package com.arcmusic.player

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arcmusic.R
import com.arcmusic.data.DownloadRecord
import com.arcmusic.data.LocalPlaylist
import com.arcmusic.ui.MainViewModel

/** What the browse tab is listing. */
private enum class BrowseMode { PLAYLISTS, ALBUMS, ARTISTS }

private fun albumOf(record: DownloadRecord): String =
    record.album?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM

private fun artistOf(record: DownloadRecord): String =
    (record.albumArtist ?: record.uploader)?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST

private const val UNKNOWN_ALBUM = "\u0000album"
private const val UNKNOWN_ARTIST = "\u0000artist"

/**
 * Playlists, albums and artists in one place, sitting between the library and search.
 * Everything is grouped on the fly from the library records, so there is no second index
 * to keep in step.
 */
@Composable
fun BrowseTab(viewModel: MainViewModel) {
    val library by viewModel.items.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()

    var mode by rememberSaveable { mutableStateOf(BrowseMode.PLAYLISTS) }
    var openAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    var openArtist by rememberSaveable { mutableStateOf<String?>(null) }
    var openPlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    val albumGroups = remember(library) {
        library.groupBy(::albumOf).entries
            .sortedWith(compareBy({ it.key == UNKNOWN_ALBUM }, { it.key.lowercase() }))
    }
    val artistGroups = remember(library) {
        library.groupBy(::artistOf).entries
            .sortedWith(compareBy({ it.key == UNKNOWN_ARTIST }, { it.key.lowercase() }))
    }

    val playlistTracks = remember(openPlaylist, playlists, library) {
        val playlist = playlists.firstOrNull { it.id == openPlaylist }
        playlist?.trackIds?.mapNotNull { id -> library.firstOrNull { it.id == id } }.orEmpty()
    }
    val fromAlbum = remember(openAlbum, albumGroups) {
        albumGroups.firstOrNull { it.key == openAlbum }?.value.orEmpty()
    }
    val fromArtist = remember(openArtist, artistGroups) {
        artistGroups.firstOrNull { it.key == openArtist }?.value.orEmpty()
    }

    // A detail view takes over the whole tab; back sorts itself out through the header.
    val detailTitle: String? = when {
        openPlaylist != null -> playlists.firstOrNull { it.id == openPlaylist }?.name
        openAlbum != null -> openAlbum?.takeIf { it != UNKNOWN_ALBUM }
            ?: stringResource(R.string.browse_unknown_album)
        openArtist != null -> openArtist?.takeIf { it != UNKNOWN_ARTIST }
            ?: stringResource(R.string.browse_unknown_artist)
        else -> null
    }
    val detailTracks = when {
        openPlaylist != null -> playlistTracks
        openAlbum != null -> fromAlbum
        openArtist != null -> fromArtist
        else -> emptyList()
    }

    if (detailTitle != null) {
        DetailScreen(
            title = detailTitle,
            tracks = detailTracks,
            currentKey = playback.current?.key,
            playing = playback.playing,
            onBack = { openPlaylist = null; openAlbum = null; openArtist = null },
            onPlay = { index -> viewModel.playRecords(detailTracks, index) },
        )
        return
    }

    if (creating) {
        CreatePlaylistDialog(
            onDismiss = { creating = false },
            onCreate = { name ->
                viewModel.createPlaylist(name)
                creating = false
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.player_tab_browse),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.browse_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (mode == BrowseMode.PLAYLISTS) {
                    IconButton(onClick = { creating = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = stringResource(R.string.browse_new_playlist),
                        )
                    }
                }
            }
        }

        item("modes") {
            BrowseModeRow(mode = mode, onChange = { mode = it })
        }

        when (mode) {
            BrowseMode.PLAYLISTS -> {
                if (playlists.isEmpty()) {
                    item("empty") { Hint(stringResource(R.string.player_playlist_empty)) }
                }
                items(playlists, key = { it.id }) { playlist ->
                    val count = remember(playlist.trackIds, library) {
                        playlist.trackIds.count { id -> library.any { it.id == id } }
                    }
                    BrowseRow(
                        title = playlist.name,
                        subtitle = stringResource(R.string.player_track_count, count),
                        artUrl = null,
                        icon = R.drawable.ic_queue,
                        onClick = { openPlaylist = playlist.id },
                    )
                }
            }

            BrowseMode.ALBUMS -> {
                if (albumGroups.isEmpty()) {
                    item("empty") { Hint(stringResource(R.string.browse_empty)) }
                }
                items(albumGroups, key = { it.key }) { (album, tracks) ->
                    BrowseRow(
                        title = if (album == UNKNOWN_ALBUM) {
                            stringResource(R.string.browse_unknown_album)
                        } else {
                            album
                        },
                        subtitle = stringResource(R.string.player_track_count, tracks.size),
                        artUrl = tracks.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
                        onClick = { openAlbum = album },
                    )
                }
            }

            BrowseMode.ARTISTS -> {
                if (artistGroups.isEmpty()) {
                    item("empty") { Hint(stringResource(R.string.browse_empty)) }
                }
                items(artistGroups, key = { it.key }) { (artist, tracks) ->
                    BrowseRow(
                        title = if (artist == UNKNOWN_ARTIST) {
                            stringResource(R.string.browse_unknown_artist)
                        } else {
                            artist
                        },
                        subtitle = stringResource(R.string.player_track_count, tracks.size),
                        artUrl = tracks.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
                        onClick = { openArtist = artist },
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowseModeRow(mode: BrowseMode, onChange: (BrowseMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BrowseMode.entries.forEach { option ->
            val selected = option == mode
            val background by androidx.compose.animation.animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
                label = "browseMode",
            )
            val content by androidx.compose.animation.animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                label = "browseModeText",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(background)
                    .clickable { onChange(option) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        when (option) {
                            BrowseMode.PLAYLISTS -> R.string.browse_playlists
                            BrowseMode.ALBUMS -> R.string.browse_albums
                            BrowseMode.ARTISTS -> R.string.browse_artists
                        }
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = content,
                )
            }
        }
    }
}

@Composable
private fun BrowseRow(
    title: String,
    subtitle: String,
    artUrl: String?,
    onClick: () -> Unit,
    icon: Int? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(modifier = Modifier.fillMaxWidth().clip(shape)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(glassRowColor())
                .pressable(onClick = onClick, showRipple = false)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Artwork(artUrl, 54.dp, RoundedCornerShape(12.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun DetailScreen(
    title: String,
    tracks: List<DownloadRecord>,
    currentKey: String?,
    playing: Boolean,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.close),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.player_track_count, tracks.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (tracks.isEmpty()) {
                item("empty") { Hint(stringResource(R.string.player_playlist_empty)) }
            }
            itemsIndexed(tracks, key = { _, record -> record.id }) { index, record ->
                val current = record.id == currentKey
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(glassRowColor())
                            .pressable(onClick = { onPlay(index) }, showRipple = false)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Artwork(record.thumbnailUrl, 46.dp, RoundedCornerShape(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                            )
                            Text(
                                text = record.uploader.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (current && playing) {
                            EqualizerBars(active = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
}


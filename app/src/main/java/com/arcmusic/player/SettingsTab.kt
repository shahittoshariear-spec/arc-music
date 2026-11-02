package com.arcmusic.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arcmusic.R
import com.arcmusic.CrashLog
import com.arcmusic.data.AppNightMode
import com.arcmusic.data.AppTheme
import com.arcmusic.data.Backup
import com.arcmusic.data.AudioFormat
import com.arcmusic.data.LyricsStyle
import com.arcmusic.ui.EngineStatus
import com.arcmusic.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.arcmusic.ui.theme.previewColor
import com.arcmusic.updater.AppUpdater
import com.arcmusic.updater.UpdateState

/** Minutes shown in the online-search length filter; 0 means no limit. */
private val MAX_LENGTH_CHOICES = listOf(0, 5, 10, 15, 20, 30, 60)

/** One tap sets accent, cards and background to this hue. */
private val QUICK_HUES = listOf(
    348f, 12f, 30f, 50f, 90f, 150f, 175f, 205f, 230f, 262f, 295f, 322f,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    viewModel: MainViewModel,
    onOpenBrowser: () -> Unit,
    onMessage: (String) -> Unit,
    onClose: () -> Unit,
) {
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val format by viewModel.format.collectAsStateWithLifecycle()
    val folder by viewModel.folder.collectAsStateWithLifecycle()
    val trim by viewModel.trimNonMusic.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val engineVersion by viewModel.engineVersion.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val gradient by viewModel.artworkGradient.collectAsStateWithLifecycle()
    val glass by viewModel.glassMode.collectAsStateWithLifecycle()
    val customAccent by viewModel.customAccent.collectAsStateWithLifecycle()
    val customBackground by viewModel.customBackground.collectAsStateWithLifecycle()
    val customSurface by viewModel.customSurface.collectAsStateWithLifecycle()
    val searchAtBottom by viewModel.searchButtonAtBottom.collectAsStateWithLifecycle()
    val maxMinutes by viewModel.maxSearchMinutes.collectAsStateWithLifecycle()
    val autoTag by viewModel.autoTag.collectAsStateWithLifecycle()
    val hqArt by viewModel.hqArtwork.collectAsStateWithLifecycle()
    val autoFormat by viewModel.autoFormat.collectAsStateWithLifecycle()
    val lyricsStyle by viewModel.lyricsStyle.collectAsStateWithLifecycle()
    val lyricsTapSeek by viewModel.lyricsTapSeek.collectAsStateWithLifecycle()
    val nightMode by viewModel.nightMode.collectAsStateWithLifecycle()
    val updateState by AppUpdater.state.collectAsStateWithLifecycle()
    val showSearch by viewModel.showSearchTab.collectAsStateWithLifecycle()
    val showBrowse by viewModel.showBrowseTab.collectAsStateWithLifecycle()
    // Swatches are previewed in the palette the app is actually drawing, not the phone's.
    val systemDark = isSystemInDarkTheme()
    val appDark = when (nightMode) {
        AppNightMode.LIGHT -> false
        AppNightMode.DARK -> true
        AppNightMode.SYSTEM -> systemDark
    }

    var folderDraft by remember(folder) { mutableStateOf(folder) }
    var showDownloads by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(Backup.export(context).toByteArray())
                    } != null
                }.getOrDefault(false)
            }
            onMessage(
                context.getString(
                    if (ok) R.string.settings_backup_exported else R.string.settings_backup_failed
                )
            )
        }
    }

    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val raw = runCatching {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                }.getOrNull()
                raw != null && Backup.restore(context, raw)
            }
            onMessage(
                context.getString(
                    if (ok) R.string.settings_backup_restored else R.string.settings_backup_failed
                )
            )
        }
    }

    val saveLog = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(CrashLog.report(context).toByteArray())
                    } != null
                }.getOrDefault(false)
            }
            if (ok) onMessage(context.getString(R.string.settings_log_saved))
        }
    }

    LaunchedEffect(Unit) { viewModel.loadEngineVersion() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                    Text(
                        text = stringResource(R.string.player_tab_settings),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

        item("updates") {
            val localVersion = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull().orEmpty()
            }
            SettingsCard(
                title = stringResource(R.string.settings_updates),
                help = stringResource(R.string.settings_updates_help, localVersion),
                icon = R.drawable.ic_refresh,
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (val update = updateState) {
                            UpdateState.Idle -> TextButton(
                                onClick = { AppUpdater.check(context) },
                            ) {
                                Text(stringResource(R.string.settings_update_check))
                            }

                            UpdateState.Checking -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = stringResource(R.string.settings_update_checking),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            is UpdateState.UpToDate -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_update_latest),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { AppUpdater.check(context) }) {
                                    Text(stringResource(R.string.settings_update_check))
                                }
                            }

                            is UpdateState.Available -> {
                                Text(
                                    text = stringResource(
                                        R.string.settings_update_available,
                                        update.info.version,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (update.info.notes.isNotBlank()) {
                                    Text(
                                        text = update.info.notes,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 8,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                TextButton(onClick = { AppUpdater.download(context) }) {
                                    Text(stringResource(R.string.settings_update_download))
                                }
                            }

                            is UpdateState.Downloading -> {
                                LinearProgressIndicator(
                                    progress = { update.progress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = stringResource(
                                        R.string.settings_update_downloading,
                                        (update.progress * 100).toInt(),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            is UpdateState.Ready -> {
                                Text(
                                    text = stringResource(R.string.settings_update_ready),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { AppUpdater.install(context) }) {
                                    Text(stringResource(R.string.settings_update_install))
                                }
                            }

                            is UpdateState.NeedsPermission -> {
                                Text(
                                    text = stringResource(R.string.settings_update_permission),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = { AppUpdater.requestInstallPermission(context) },
                                    ) {
                                        Text(stringResource(R.string.settings_update_open_settings))
                                    }
                                    TextButton(onClick = { AppUpdater.install(context) }) {
                                        Text(stringResource(R.string.settings_update_install))
                                    }
                                }
                            }

                            is UpdateState.Failed -> {
                                Text(
                                    text = stringResource(
                                        R.string.settings_update_failed,
                                        update.reason,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(onClick = { AppUpdater.check(context) }) {
                                    Text(stringResource(R.string.settings_update_check))
                                }
                            }
                        }
                    }
                },
            )
        }

        item("session") {
            SettingsCard(
                title = stringResource(R.string.session_title),
                help = stringResource(
                    if (signedIn) R.string.session_note else R.string.session_none
                ),
                icon = R.drawable.ic_account,
                content = {
                    TextButton(onClick = onOpenBrowser) {
                        Text(stringResource(R.string.player_open_youtube))
                    }
                },
            )
        }

        item("appearance") {
            SettingsCard(
                title = stringResource(R.string.player_settings_appearance),
                help = stringResource(R.string.player_settings_appearance_help),
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.player_settings_night_mode),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                AppNightMode.entries.forEach { option ->
                                    PlacementChip(
                                        label = stringResource(option.labelRes),
                                        selected = option == nightMode,
                                    ) { viewModel.setNightMode(option) }
                                }
                            }
                        }

                        // Three favourites plus a door to the rest: the row stays
                        // readable however many palettes exist.
                        val featured = listOf(
                            AppTheme.CRIMSON,
                            AppTheme.OCEAN,
                            AppTheme.P3R,
                            AppTheme.CELESTIAL,
                        )
                        val shown = if (theme in featured) featured else featured.dropLast(1) + theme
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            shown.forEach { option ->
                                ThemeSwatch(
                                    theme = option,
                                    color = if (option == AppTheme.CUSTOM) {
                                        androidx.compose.ui.graphics.Color.hsv(
                                            customAccent.mod(360f), 0.7f, 0.95f,
                                        )
                                    } else {
                                        option.previewColor(appDark)
                                    },
                                    selected = option == theme,
                                    onClick = { viewModel.setTheme(option) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .clickable { showThemes = true },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_more),
                                        contentDescription = stringResource(R.string.theme_more),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.theme_more),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }

                        AnimatedVisibility(visible = theme == AppTheme.CUSTOM) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(R.string.player_theme_quick),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    QUICK_HUES.forEach { hue ->
                                        val picked = kotlin.math.abs(hue - customAccent) < 1f
                                        Box(
                                            modifier = Modifier
                                                .size(30.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    androidx.compose.ui.graphics.Color.hsv(
                                                        hue, 0.7f, 0.95f,
                                                    )
                                                )
                                                .border(
                                                    width = if (picked) 2.5.dp else 0.dp,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    shape = CircleShape,
                                                )
                                                .clickable {
                                                    // One tap sets the whole palette to that
                                                    // hue; the sliders then fine-tune it.
                                                    viewModel.setCustomAccent(hue)
                                                    viewModel.setCustomBackground(hue)
                                                    viewModel.setCustomSurface(hue)
                                                },
                                        )
                                    }
                                }
                                HueSlider(
                                    label = stringResource(R.string.player_settings_custom_accent),
                                    hue = customAccent,
                                    onHue = viewModel::setCustomAccent,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start,
                                ) {
                                    TextButton(
                                        onClick = {
                                            viewModel.setCustomBackground(customAccent)
                                            viewModel.setCustomSurface(customAccent)
                                        },
                                    ) {
                                        Text(stringResource(R.string.player_theme_match_accent))
                                    }
                                }
                                HueSlider(
                                    label = stringResource(R.string.player_settings_custom_background),
                                    hue = customBackground,
                                    onHue = viewModel::setCustomBackground,
                                )
                                HueSlider(
                                    label = stringResource(R.string.player_settings_custom_surface),
                                    hue = customSurface,
                                    onHue = viewModel::setCustomSurface,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.player_settings_gradient),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(R.string.player_settings_gradient_help),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = gradient, onCheckedChange = viewModel::setArtworkGradient)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.player_settings_glass),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(R.string.player_settings_glass_help),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = glass,
                                onCheckedChange = viewModel::setGlassMode,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.player_settings_search_button),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(R.string.player_settings_search_button_help),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PlacementChip(
                                    label = stringResource(R.string.player_search_button_top),
                                    selected = !searchAtBottom,
                                ) { viewModel.setSearchButtonAtBottom(false) }
                                PlacementChip(
                                    label = stringResource(R.string.player_search_button_bottom),
                                    selected = searchAtBottom,
                                ) { viewModel.setSearchButtonAtBottom(true) }
                            }
                        }
                    }
                },
            )
        }

        item("navigation") {
            SettingsCard(
                title = stringResource(R.string.player_settings_tabs),
                help = stringResource(R.string.player_settings_tabs_help),
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ToggleRow(
                            title = stringResource(R.string.player_tab_search),
                            help = stringResource(R.string.player_settings_show_search_help),
                            checked = showSearch,
                            onChange = viewModel::setShowSearchTab,
                        )
                        ToggleRow(
                            title = stringResource(R.string.player_tab_browse),
                            help = stringResource(R.string.player_settings_show_browse_help),
                            checked = showBrowse,
                            onChange = viewModel::setShowBrowseTab,
                        )
                    }
                },
            )
        }

        item("autotag") {
            val status by viewModel.autoTagStatus.collectAsStateWithLifecycle()
            SettingsCard(
                title = stringResource(R.string.player_settings_autotag),
                help = when {
                    status.tagged == 0 && status.skipped > 0 && status.lastIssue != null ->
                        stringResource(R.string.player_autotag_issue, status.lastIssue.orEmpty())

                    status.tagged + status.skipped > 0 -> stringResource(
                        R.string.player_autotag_status,
                        status.tagged,
                        status.skipped,
                    )

                    status.queued > 0 -> stringResource(
                        R.string.player_autotag_waiting,
                        status.queued,
                    )

                    else -> stringResource(R.string.player_settings_autotag_help)
                },
                trailing = {
                    Switch(checked = autoTag, onCheckedChange = viewModel::setAutoTag)
                },
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ToggleRow(
                            title = stringResource(R.string.player_autotag_hq_art),
                            help = stringResource(R.string.player_autotag_hq_art_help),
                            checked = hqArt,
                            onChange = viewModel::setHqArtwork,
                        )
                        if (autoTag) {
                            TextButton(onClick = viewModel::runAutoTagOnLibrary) {
                                Text(stringResource(R.string.player_autotag_run))
                            }
                        }
                    }
                },
            )
        }

        item("downloads") {
            val active = queue.count { it.isActive }
            SettingsCard(
                title = stringResource(R.string.player_downloads),
                help = if (active > 0) {
                    stringResource(R.string.player_downloads_active, active)
                } else {
                    stringResource(R.string.player_downloads_empty)
                },
                content = {
                    TextButton(onClick = { showDownloads = true }) {
                        Text(stringResource(R.string.player_downloads))
                    }
                },
            )
        }

        item("lyrics") {
            SettingsCard(
                title = stringResource(R.string.player_settings_lyrics),
                help = stringResource(R.string.player_settings_lyrics_help),
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        LyricsStyle.entries.forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { viewModel.setLyricsStyle(option) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                RadioButton(
                                    selected = option == lyricsStyle,
                                    onClick = { viewModel.setLyricsStyle(option) },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(option.label),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = stringResource(option.blurb),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        ToggleRow(
                            title = stringResource(R.string.player_settings_lyrics_tap),
                            help = stringResource(R.string.player_settings_lyrics_tap_help),
                            checked = lyricsTapSeek,
                            onChange = viewModel::setLyricsTapSeek,
                        )
                    }
                },
            )
        }

        item("quality") {
            SettingsCard(
                title = stringResource(R.string.player_settings_quality_auto),
                help = stringResource(R.string.player_settings_quality_auto_help),
                trailing = {
                    Switch(checked = autoFormat, onCheckedChange = viewModel::setAutoFormat)
                },
                content = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AudioFormat.entries.forEach { option ->
                            FilterChip(
                                selected = option == format,
                                onClick = { viewModel.onFormatChange(option) },
                                label = { Text(stringResource(option.labelRes)) },
                            )
                        }
                    }
                },
            )
        }

        item("searchlimit") {
            SettingsCard(
                title = stringResource(R.string.player_settings_max_length),
                help = stringResource(R.string.player_settings_max_length_help),
                content = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MAX_LENGTH_CHOICES.forEach { minutes ->
                            PlacementChip(
                                label = if (minutes == 0) {
                                    stringResource(R.string.player_settings_max_length_off)
                                } else {
                                    stringResource(R.string.player_settings_max_length_minutes, minutes)
                                },
                                selected = maxMinutes == minutes,
                            ) { viewModel.setMaxSearchMinutes(minutes) }
                        }
                    }
                },
            )
        }

        item("folder") {
            SettingsCard(
                title = stringResource(R.string.player_settings_library),
                help = stringResource(R.string.saved_to, folder),
                content = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = folderDraft,
                            onValueChange = { folderDraft = it },
                            placeholder = { Text(stringResource(R.string.player_settings_folder_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { viewModel.setFolder(folderDraft) },
                            enabled = folderDraft != folder,
                        ) {
                            Text(stringResource(R.string.player_apply))
                        }
                    }
                },
            )
        }

        item("trim") {
            SettingsCard(
                title = stringResource(R.string.settings_sponsorblock),
                help = stringResource(R.string.settings_sponsorblock_help),
                trailing = {
                    Switch(checked = trim, onCheckedChange = viewModel::setTrimNonMusic)
                },
            )
        }

        item("engine") {
            SettingsCard(
                title = stringResource(R.string.settings_engine),
                help = stringResource(R.string.settings_engine_help),
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::updateEngine,
                            enabled = engineStatus != EngineStatus.UPDATING,
                        ) {
                            if (engineStatus == EngineStatus.UPDATING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_refresh),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Text(
                                text = stringResource(
                                    if (engineStatus == EngineStatus.UPDATING) {
                                        R.string.engine_updating
                                    } else {
                                        R.string.settings_engine_update
                                    }
                                ),
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }

                        when (engineStatus) {
                            EngineStatus.UPDATED -> Text(
                                text = stringResource(R.string.engine_updated),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )

                            EngineStatus.FAILED -> Text(
                                text = stringResource(R.string.engine_failed),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )

                            else -> Unit
                        }

                        engineVersion?.let { version ->
                            Text(
                                text = stringResource(R.string.engine_version, version),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        }

        item("backup") {
            SettingsCard(
                title = stringResource(R.string.settings_backup),
                help = stringResource(R.string.settings_backup_help),
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { exportBackup.launch("a-rc-backup.json") },
                            ) {
                                Text(stringResource(R.string.settings_backup_export))
                            }
                            TextButton(
                                onClick = {
                                    importBackup.launch(
                                        arrayOf("application/json", "text/plain", "*/*")
                                    )
                                },
                            ) {
                                Text(stringResource(R.string.settings_backup_restore))
                            }
                        }
                        TextButton(onClick = viewModel::redownloadMissing) {
                            Text(stringResource(R.string.settings_redownload))
                        }
                    }
                },
            )
        }

        item("diagnostics") {
            SettingsCard(
                title = stringResource(R.string.settings_diagnostics),
                help = stringResource(R.string.settings_diagnostics_help),
                content = {
                    TextButton(onClick = { saveLog.launch("a-rc-log.txt") }) {
                        Text(stringResource(R.string.settings_save_log))
                    }
                },
            )
        }

        item("storage") {
            SettingsCard(
                title = stringResource(R.string.player_storage),
                help = stringResource(R.string.player_clear_all_help),
                content = {
                    TextButton(
                        onClick = { confirmClear = true },
                    ) {
                        Text(
                            text = stringResource(R.string.player_clear_all),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }

        item("stats") {
            SettingsCard(
                title = stringResource(R.string.player_stats_reset),
                help = stringResource(R.string.player_stats_reset_help),
                icon = R.drawable.ic_stats,
                content = {
                    TextButton(onClick = { confirmReset = true }) {
                        Text(
                            text = stringResource(R.string.player_stats_reset),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }

        item("about") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.about_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.about_blurb),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
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

    if (showThemes) {
        ModalBottomSheet(onDismissRequest = { showThemes = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.theme_all),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val dark = appDark
                AppTheme.entries.chunked(4).forEach { line ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        line.forEach { option ->
                            ThemeSwatch(
                                theme = option,
                                color = if (option == AppTheme.CUSTOM) {
                                    androidx.compose.ui.graphics.Color.hsv(
                                        customAccent.mod(360f), 0.7f, 0.95f,
                                    )
                                } else {
                                    option.previewColor(dark)
                                },
                                selected = option == theme,
                                onClick = {
                                    viewModel.setTheme(option)
                                    showThemes = false
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(4 - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.player_clear_all_title)) },
            text = { Text(stringResource(R.string.player_clear_all_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clearAll()
                        onMessage(context.getString(R.string.player_cleared))
                    },
                ) {
                    Text(
                        text = stringResource(R.string.player_clear_all_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.player_stats_reset)) },
            text = { Text(stringResource(R.string.player_stats_reset_help)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetStats()
                        confirmReset = false
                    },
                ) {
                    Text(stringResource(R.string.player_stats_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun PlacementChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        label = "placementContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "placementContent",
    )
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = content,
        )
    }
}

/** One hue picker: a rainbow guide with a slider underneath. */
@Composable
private fun HueSlider(label: String, hue: Float, onHue: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(
                        androidx.compose.ui.graphics.Color.hsv(hue.mod(360f), 0.7f, 0.95f)
                    ),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map {
                            androidx.compose.ui.graphics.Color.hsv(it.mod(360f), 0.7f, 0.95f)
                        }
                    )
                ),
        )
        Slider(
            value = hue,
            onValueChange = onHue,
            valueRange = 0f..360f,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ThemeSwatch(
    theme: AppTheme,
    color: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val selectedScale by animateFloatAsState(
            targetValue = if (selected) 1.12f else 1f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
            label = "swatchScale",
        )
        Box(
            modifier = Modifier
                .size(30.dp)
                .graphicsLayer {
                    scaleX = selectedScale
                    scaleY = selectedScale
                }
                .clip(CircleShape)
                .background(
                    if (theme.isSpecial) {
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            listOf(
                                color.copy(alpha = 0.72f),
                                color,
                                color.copy(alpha = 0.55f),
                            )
                        )
                    } else {
                        androidx.compose.ui.graphics.SolidColor(color)
                    }
                )
                .then(
                    if (theme.isSpecial) {
                        Modifier.border(
                            1.5.dp,
                            androidx.compose.ui.graphics.Color.White
                                .copy(alpha = 0.55f),
                            CircleShape,
                        )
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (selected) {
                        Modifier.border(
                            2.5.dp,
                            MaterialTheme.colorScheme.onSurface,
                            CircleShape,
                        )
                    } else {
                        Modifier
                    }
                )
                .clickable(onClick = onClick),
        )
        Text(
            text = stringResource(theme.label),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    help: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = help,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsCard(
    title: String,
    help: String,
    icon: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                androidx.compose.ui.graphics.Color.White.copy(alpha = 0.09f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (icon != null) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = help,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                trailing?.invoke()
            }
            content?.invoke()
        }
    }
}

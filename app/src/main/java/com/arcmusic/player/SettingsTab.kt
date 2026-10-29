package com.arcmusic.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arcmusic.R
import com.arcmusic.data.AudioFormat
import com.arcmusic.ui.EngineStatus
import com.arcmusic.ui.MainViewModel

@Composable
fun SettingsTab(
    viewModel: MainViewModel,
    onOpenBrowser: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val online by viewModel.onlineMode.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val format by viewModel.format.collectAsStateWithLifecycle()
    val folder by viewModel.folder.collectAsStateWithLifecycle()
    val trim by viewModel.trimNonMusic.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val engineVersion by viewModel.engineVersion.collectAsStateWithLifecycle()

    var folderDraft by remember(folder) { mutableStateOf(folder) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.loadEngineVersion() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("header") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.player_tab_settings),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        item("online") {
            SettingsCard(
                title = stringResource(R.string.player_settings_online),
                help = stringResource(R.string.player_settings_online_help),
                trailing = {
                    Switch(checked = online, onCheckedChange = viewModel::setOnlineMode)
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

        item("quality") {
            SettingsCard(
                title = stringResource(R.string.player_settings_quality),
                help = stringResource(R.string.player_settings_quality_help),
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

        item("storage") {
            SettingsCard(
                title = stringResource(R.string.player_storage),
                help = stringResource(R.string.player_clear_all_help),
                content = {
                    TextButton(
                        onClick = {
                            viewModel.clearAll()
                            onMessage(context.getString(R.string.player_cleared))
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.player_clear_all),
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

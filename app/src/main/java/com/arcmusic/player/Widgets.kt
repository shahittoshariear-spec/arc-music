package com.arcmusic.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.arcmusic.R
import com.arcmusic.data.AudioFormat
import com.arcmusic.data.DownloadRecord
import com.arcmusic.downloader.JobStatus
import com.arcmusic.downloader.MediaInfo
import com.arcmusic.downloader.QueueItem
import java.util.Locale

/** What the download button should show for one search result. */
sealed interface DownloadVisual {
    data object Idle : DownloadVisual
    data object Queued : DownloadVisual
    data class Active(val progress: Float) : DownloadVisual
    data object Done : DownloadVisual
    data object Failed : DownloadVisual
}

/**
 * A result is "saved" when it is in the library, and "downloading" while its job sits
 * in the queue. Matching on the page URL is why [DownloadRecord.sourceUrl] exists.
 */
fun downloadVisualFor(
    info: MediaInfo,
    queue: List<QueueItem>,
    library: List<DownloadRecord>,
): DownloadVisual {
    val job = queue.lastOrNull { it.url == info.url }
    if (job != null) {
        return when (job.status) {
            JobStatus.FAILED -> DownloadVisual.Failed
            JobStatus.CANCELED -> DownloadVisual.Idle
            JobStatus.FINISHED -> DownloadVisual.Done
            JobStatus.QUEUED -> DownloadVisual.Queued
            JobStatus.RUNNING -> DownloadVisual.Active(job.displayProgress)
        }
    }
    return if (library.any { it.sourceUrl == info.url }) DownloadVisual.Done else DownloadVisual.Idle
}

@Composable
fun Artwork(url: String?, size: Dp, shape: Shape = RoundedCornerShape(10.dp)) {
    Artwork(url = url, modifier = Modifier.size(size), shape = shape, iconSize = size * 0.36f)
}

@Composable
fun Artwork(
    url: String?,
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(iconSize),
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The Google Play download control: a ring that fills as the job runs, wrapped around
 * a button that becomes a check once the song is in the library.
 */
@Composable
fun DownloadDial(state: DownloadVisual, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val busy = state is DownloadVisual.Active || state is DownloadVisual.Queued

    Box(modifier = modifier.size(44.dp), contentAlignment = Alignment.Center) {
        when (state) {
            is DownloadVisual.Active -> {
                if (state.progress < 0f) {
                    CircularProgressIndicator(modifier = Modifier.fillMaxSize(), strokeWidth = 3.dp)
                } else {
                    val animated by animateFloatAsState(
                        targetValue = state.progress.coerceIn(0f, 1f),
                        animationSpec = tween(durationMillis = 500),
                        label = "downloadProgress",
                    )
                    CircularProgressIndicator(
                        progress = { animated },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 3.dp,
                        trackColor = colors.surfaceContainerHighest,
                    )
                }
            }

            is DownloadVisual.Queued -> CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 2.dp,
                color = colors.outline,
            )

            else -> Unit
        }

        Surface(
            shape = CircleShape,
            color = when (state) {
                is DownloadVisual.Done -> colors.primary
                is DownloadVisual.Failed -> colors.errorContainer
                else -> colors.surfaceContainerHighest
            },
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(enabled = !busy, onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) {
                val phase = when (state) {
                    is DownloadVisual.Idle -> 0
                    is DownloadVisual.Queued -> 1
                    is DownloadVisual.Active -> 2
                    is DownloadVisual.Done -> 3
                    is DownloadVisual.Failed -> 4
                }
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = {
                        (fadeIn(tween(180)) + scaleIn(initialScale = 0.7f)) togetherWith
                            (fadeOut(tween(120)) + scaleOut(targetScale = 0.7f))
                    },
                    label = "dialIcon",
                ) { current ->
                    Icon(
                        painter = painterResource(
                            when (current) {
                                3 -> R.drawable.ic_check
                                4 -> R.drawable.ic_refresh
                                else -> R.drawable.ic_download
                            }
                        ),
                        contentDescription = stringResource(R.string.browse_download),
                        tint = when (current) {
                            3 -> colors.onPrimary
                            4 -> colors.onErrorContainer
                            else -> colors.onSurfaceVariant
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatSheet(onDismiss: () -> Unit, onPick: (AudioFormat) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.player_download_as),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
            AudioFormat.entries.forEach { format ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onPick(format) }
                        .padding(horizontal = 8.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = format.shortName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(format.labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(format.blurbRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A frosted panel: translucent fill, hairline highlight and a soft drop shadow.
 *
 * True backdrop blur needs a RenderEffect, so this leans on translucency plus a
 * bright edge, which reads as glass on both schemes and costs nothing on older APIs.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    strong: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fill = when {
        dark && strong -> Color.White.copy(alpha = 0.11f)
        dark -> Color.White.copy(alpha = 0.06f)
        strong -> Color.White.copy(alpha = 0.88f)
        else -> Color.White.copy(alpha = 0.68f)
    }
    val edge = if (dark) {
        Color.White.copy(alpha = 0.12f)
    } else {
        Color.White.copy(alpha = 0.95f)
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = 18.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.22f),
            )
            .clip(shape)
            .background(fill, shape)
            .border(0.8.dp, edge, shape),
        content = content,
    )
}

/**
 * A tap that gives a little: the surface eases down and springs back. Used on every
 * row and card so touches feel like the rest of the app's motion.
 */
@Composable
fun Modifier.pressable(onClick: () -> Unit, pressedScale: Float = 0.97f): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = ripple(),
            onClick = onClick,
        )
}

/** Up to four artworks in a 2×2 square, the way Apple Music shows a playlist. */
@Composable
fun PlaylistArtwork(
    urls: List<String?>,
    size: Dp,
    shape: Shape = RoundedCornerShape(14.dp),
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (urls.all { it.isNullOrBlank() }) {
            Icon(
                painter = painterResource(R.drawable.ic_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(size * 0.3f),
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    PlaylistCell(urls.getOrNull(0), Modifier.weight(1f).fillMaxHeight())
                    PlaylistCell(urls.getOrNull(1), Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    PlaylistCell(urls.getOrNull(2), Modifier.weight(1f).fillMaxHeight())
                    PlaylistCell(urls.getOrNull(3), Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun PlaylistCell(url: String?, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    else -> "${bytes / 1000} KB"
}

fun formatClock(totalSeconds: Long): String {
    if (totalSeconds <= 0L) return "0:00"
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

fun formatClockMillis(ms: Long): String = formatClock(ms / 1000)

package com.arcmusic.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arcmusic.R
import com.arcmusic.data.StatsBucket
import com.arcmusic.data.StatsRange
import com.arcmusic.data.TrackStat
import com.arcmusic.ui.MainViewModel

/** What the ranking page should show: a bar's window and its title. */
data class StatsRanking(val title: String, val start: Long, val end: Long)

/** The two graphs the stats tab can draw. */
enum class StatsMetric { PLAYS, MINUTES }

@Composable
fun StatsTab(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenRanking: (StatsRanking) -> Unit,
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val library by viewModel.items.collectAsStateWithLifecycle()

    var range by rememberSaveable { mutableStateOf(StatsRange.WEEK) }
    var metric by rememberSaveable { mutableStateOf(StatsMetric.PLAYS) }
    val buckets = remember(stats, range, metric) {
        if (metric == StatsMetric.PLAYS) stats.buckets(range) else stats.minuteBuckets(range)
    }
    val most = remember(stats) { stats.mostPlayed(10) }
    val peak = remember(buckets) { buckets.maxOfOrNull { it.plays } ?: 0 }
    // Counts tick up instead of switching, which makes a new range feel measured.
    val totalPlays by animateIntAsState(
        targetValue = stats.total,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "totalPlays",
    )
    val totalMinutes by animateIntAsState(
        targetValue = stats.totalMinutes,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "totalMinutes",
    )
    val uniqueSongs by animateIntAsState(
        targetValue = stats.tracks.size,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "uniqueSongs",
    )
    val topArtist = remember(stats) {
        stats.tracks
            .groupBy { it.artist?.takeIf { artist -> artist.isNotBlank() } ?: "Unknown" }
            .maxByOrNull { (_, list) -> list.sumOf { it.plays } }
            ?.key
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.player_stats),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.player_stats_most),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = stringResource(R.string.settings),
                    )
                }
            }
        }

        item("summary") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (metric == StatsMetric.MINUTES) {
                    StatCard(
                        label = stringResource(R.string.player_stats_total_minutes),
                        value = totalMinutes.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = stringResource(R.string.player_stats_total),
                        value = totalPlays.toString(),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    StatCard(
                        label = stringResource(R.string.player_stats_total),
                        value = totalPlays.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = stringResource(R.string.player_stats_unique),
                        value = uniqueSongs.toString(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        topArtist?.let { artist ->
            item("artist") {
                StatCard(
                    label = stringResource(R.string.player_stats_top_artist),
                    value = artist,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item("metric") {
            MetricSwitch(metric = metric, onChange = { metric = it })
        }

        item("range") {
            RangeSwitch(range = range, onChange = { range = it })
        }

        item("chart") {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(range.caption()),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.player_stats_tap_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(148.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (buckets.size > 8) 4.dp else 8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        buckets.forEachIndexed { index, bucket ->
                            StatBar(
                                bucket = bucket,
                                peak = peak,
                                index = index,
                                minutes = metric == StatsMetric.MINUTES,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    onOpenRanking(
                                        StatsRanking(bucket.title, bucket.start, bucket.end)
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        item("most-header") {
            Text(
                text = stringResource(R.string.player_stats_most),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (most.isEmpty()) {
            item("empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_stats),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = stringResource(R.string.player_stats_empty),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.player_stats_empty_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            itemsIndexed(most, key = { _, stat -> stat.key }) { _, stat ->
                val record = library.firstOrNull { it.id == stat.key }
                val playAction: (() -> Unit)? = record?.let { saved ->
                    { viewModel.playRecords(listOf(saved), 0) }
                }
                StatRow(stat = stat, onPlay = playAction)
            }
        }
    }
}

/** The drill-in page behind a bar: who was played inside that window, and how often. */
@Composable
fun RankingScreen(viewModel: MainViewModel, ranking: StatsRanking, onClose: () -> Unit) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val library by viewModel.items.collectAsStateWithLifecycle()
    val ranked = remember(stats, ranking) { stats.ranking(ranking.start, ranking.end) }
    val plays = remember(ranked) { ranked.sumOf { it.plays } }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainer,
                            MaterialTheme.colorScheme.background,
                        )
                    )
                )
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item("header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(
                                painter = painterResource(R.drawable.ic_chevron_down),
                                contentDescription = stringResource(R.string.close),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = ranking.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(R.string.player_stats_plays, plays),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.size(48.dp))
                    }
                }

                if (ranked.isEmpty()) {
                    item("empty") {
                        Text(
                            text = stringResource(R.string.player_stats_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp),
                        )
                    }
                } else {
                    itemsIndexed(ranked, key = { _, stat -> stat.key }) { index, stat ->
                        val record = library.firstOrNull { it.id == stat.key }
                        val playAction: (() -> Unit)? = record?.let { saved ->
                            { viewModel.playRecords(listOf(saved), 0) }
                        }
                        RankingRow(
                            rank = index + 1,
                            stat = stat,
                            onPlay = playAction,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBar(
    bucket: StatsBucket,
    peak: Int,
    index: Int,
    minutes: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ratio = if (peak <= 0) 0f else bucket.plays.toFloat() / peak
    val delay = (index * 26).coerceAtMost(240)
    val height by animateFloatAsState(
        targetValue = 4f + ratio * 92f,
        animationSpec = tween(
            durationMillis = 560,
            delayMillis = delay,
            easing = FastOutSlowInEasing,
        ),
        label = "barHeight",
    )
    val count by animateIntAsState(
        targetValue = bucket.plays,
        animationSpec = tween(durationMillis = 560, delayMillis = delay),
        label = "barCount",
    )
    val labelColor by animateColorAsState(
        targetValue = if (bucket.plays > 0) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(360),
        label = "barLabel",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = when {
                count <= 0 -> ""
                minutes -> compactMinutes(count)
                else -> count.toString()
            },
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(
                    if (bucket.plays > 0) {
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            )
                        )
                    }
                )
        )
        Text(
            text = bucket.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun MetricSwitch(metric: StatsMetric, onChange: (StatsMetric) -> Unit) {
    val options = StatsMetric.entries
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(modifier = Modifier.padding(3.dp)) {
            options.forEach { option ->
                val selected = option == metric
                val container by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    animationSpec = tween(280),
                    label = "metricContainer",
                )
                val content by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(280),
                    label = "metricContent",
                )
                Surface(
                    shape = CircleShape,
                    color = container,
                    contentColor = content,
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .clickable { onChange(option) },
                ) {
                    Text(
                        text = stringResource(
                            if (option == StatsMetric.PLAYS) {
                                R.string.player_stats_metric_plays
                            } else {
                                R.string.player_stats_metric_minutes
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RangeSwitch(range: StatsRange, onChange: (StatsRange) -> Unit) {
    val options = StatsRange.entries
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(modifier = Modifier.padding(3.dp)) {
            options.forEach { option ->
                val selected = option == range
                val container by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    animationSpec = tween(280),
                    label = "rangeContainer",
                )
                val content by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(280),
                    label = "rangeContent",
                )
                Surface(
                    shape = CircleShape,
                    color = container,
                    contentColor = content,
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .clickable { onChange(option) },
                ) {
                    Text(
                        text = stringResource(option.label()),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private fun compactMinutes(minutes: Int): String = if (minutes >= 60) {
    (minutes / 60).toString() + "h"
} else {
    minutes.toString() + "m"
}

private fun StatsRange.label(): Int = when (this) {
    StatsRange.DAY -> R.string.player_stats_range_day
    StatsRange.WEEK -> R.string.player_stats_range_week
    StatsRange.MONTH -> R.string.player_stats_range_month
    StatsRange.YEAR -> R.string.player_stats_range_year
}

private fun StatsRange.caption(): Int = when (this) {
    StatsRange.DAY -> R.string.player_stats_week
    StatsRange.WEEK -> R.string.player_stats_weeks
    StatsRange.MONTH -> R.string.player_stats_months
    StatsRange.YEAR -> R.string.player_stats_years
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatRow(stat: TrackStat, onPlay: (() -> Unit)?) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onPlay != null) Modifier.pressable(onClick = onPlay) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Artwork(stat.artUrl, 48.dp, RoundedCornerShape(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stat.title.ifBlank { stat.key },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stat.artist.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stat.plays.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RankingRow(
    rank: Int,
    stat: TrackStat,
    onPlay: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onPlay != null) Modifier.pressable(onClick = onPlay) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = rank.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
                textAlign = TextAlign.Center,
            )
            Artwork(stat.artUrl, 44.dp, RoundedCornerShape(9.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stat.title.ifBlank { stat.key },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stat.artist.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stat.plays.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

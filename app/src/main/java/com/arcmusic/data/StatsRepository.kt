package com.arcmusic.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** How often one song has been started. */
data class TrackStat(
    val key: String,
    val title: String,
    val artist: String?,
    val artUrl: String?,
    val plays: Int,
    val lastPlayed: Long,
)

data class DayPlays(val date: LocalDate, val plays: Int)

/** One bar on the stats graph, with the window it covers. */
data class StatsBucket(
    val label: String,
    val title: String,
    val start: Long,
    val end: Long,
    val plays: Int,
)

enum class StatsRange { DAY, WEEK, MONTH, YEAR }

/** One play, kept so a bucket can be drilled into later. */
data class PlayEvent(
    val key: String,
    val title: String,
    val artist: String?,
    val artUrl: String?,
    val at: Long,
    /** Length of the play, used for the minutes graph. */
    val seconds: Int = 0,
)

data class StatsData(
    val tracks: List<TrackStat> = emptyList(),
    val daily: Map<String, Int> = emptyMap(),
    val total: Int = 0,
    val events: List<PlayEvent> = emptyList(),
    val dailySeconds: Map<String, Long> = emptyMap(),
) {
    fun playsFor(key: String): Int = tracks.firstOrNull { it.key == key }?.plays ?: 0

    fun mostPlayed(limit: Int): List<TrackStat> = tracks
        .sortedWith(compareByDescending<TrackStat> { it.plays }.thenByDescending { it.lastPlayed })
        .take(limit)

    fun recentlyPlayed(limit: Int): List<TrackStat> = tracks
        .sortedByDescending { it.lastPlayed }
        .take(limit)

    /** Whole minutes across everything, for the summary card. */
    val totalMinutes: Int
        get() = (dailySeconds.values.sum() / 60L).toInt()

    fun buckets(range: StatsRange): List<StatsBucket> =
        buildBuckets(range) { date -> daily[date.toString()] ?: 0 }

    /** The same bars, measured in whole minutes instead of plays. */
    fun minuteBuckets(range: StatsRange): List<StatsBucket> =
        buildBuckets(range) { date -> ((dailySeconds[date.toString()] ?: 0L) / 60L).toInt() }

    private fun buildBuckets(
        range: StatsRange,
        perDay: (LocalDate) -> Int,
    ): List<StatsBucket> {
        val zone = ZoneId.systemDefault()
        val locale = Locale.getDefault()
        val today = LocalDate.now()
        return when (range) {
            StatsRange.DAY -> (6 downTo 0).map { offset ->
                val date = today.minusDays(offset.toLong())
                StatsBucket(
                    label = date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                    title = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)),
                    start = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                    end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                    plays = perDay(date),
                )
            }

            StatsRange.WEEK -> {
                val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
                (7 downTo 0).map { offset ->
                    val startDate = monday.minusWeeks(offset.toLong())
                    val endDate = startDate.plusWeeks(1)
                    StatsBucket(
                        label = startDate.format(DateTimeFormatter.ofPattern("d/M", locale)),
                        title = "Week of " + startDate.format(
                            DateTimeFormatter.ofPattern("d MMMM", locale)
                        ),
                        start = startDate.atStartOfDay(zone).toInstant().toEpochMilli(),
                        end = endDate.atStartOfDay(zone).toInstant().toEpochMilli(),
                        plays = sumRange(startDate, endDate, perDay),
                    )
                }
            }

            StatsRange.MONTH -> {
                val thisMonth = YearMonth.now()
                (11 downTo 0).map { offset ->
                    val month = thisMonth.minusMonths(offset.toLong())
                    val startDate = month.atDay(1)
                    val endDate = month.plusMonths(1).atDay(1)
                    StatsBucket(
                        label = startDate.format(DateTimeFormatter.ofPattern("MMM", locale)),
                        title = startDate.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)),
                        start = startDate.atStartOfDay(zone).toInstant().toEpochMilli(),
                        end = endDate.atStartOfDay(zone).toInstant().toEpochMilli(),
                        plays = sumRange(startDate, endDate, perDay),
                    )
                }
            }

            StatsRange.YEAR -> {
                val thisYear = today.year
                (4 downTo 0).map { offset ->
                    val year = thisYear - offset
                    val startDate = LocalDate.of(year, 1, 1)
                    val endDate = LocalDate.of(year + 1, 1, 1)
                    StatsBucket(
                        label = year.toString(),
                        title = year.toString(),
                        start = startDate.atStartOfDay(zone).toInstant().toEpochMilli(),
                        end = endDate.atStartOfDay(zone).toInstant().toEpochMilli(),
                        plays = sumRange(startDate, endDate, perDay),
                    )
                }
            }
        }
    }

    /** Songs played inside one bar's window, ranked. */
    fun ranking(start: Long, end: Long): List<TrackStat> = events
        .asSequence()
        .filter { it.at >= start && it.at < end }
        .groupBy { it.key }
        .map { (key, plays) ->
            val latest = plays.maxByOrNull { it.at }!!
            TrackStat(
                key = key,
                title = latest.title,
                artist = latest.artist,
                artUrl = latest.artUrl,
                plays = plays.size,
                lastPlayed = latest.at,
            )
        }
        .sortedByDescending { it.plays }
        .take(50)

    private fun sumRange(
        startDate: LocalDate,
        endExclusive: LocalDate,
        perDay: (LocalDate) -> Int,
    ): Int {
        var date = startDate
        var sum = 0
        while (date.isBefore(endExclusive)) {
            sum += perDay(date)
            date = date.plusDays(1)
        }
        return sum
    }
}

/**
 * Listening history. Two aggregates (per song and per day) keep the library and the
 * graph cheap, and a capped event log is what lets a bar be drilled into for a ranking.
 */
class StatsRepository private constructor(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<StatsData> = _state.asStateFlow()

    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    fun record(
        key: String,
        title: String,
        artist: String?,
        artUrl: String?,
        durationSeconds: Long = 0L,
    ) {
        if (key.isBlank()) return
        val current = _state.value
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toString()
        // A bad duration would poison the minutes graph; an hour is a safe ceiling.
        val seconds = durationSeconds.coerceIn(0L, 3_600L).toInt()

        val existing = current.tracks.firstOrNull { it.key == key }
        val updated = existing?.copy(
            title = title.ifBlank { existing.title },
            artist = artist ?: existing.artist,
            artUrl = artUrl ?: existing.artUrl,
            plays = existing.plays + 1,
            lastPlayed = now,
        ) ?: TrackStat(key, title, artist, artUrl, plays = 1, lastPlayed = now)

        val tracks = (current.tracks.filterNot { it.key == key } + updated)
            .sortedByDescending { it.plays }
            .take(MAX_TRACKS)

        val daily = current.daily.toMutableMap()
        daily[today] = (daily[today] ?: 0) + 1
        val trimmed = daily.entries
            .sortedByDescending { it.key }
            .take(MAX_DAYS)
            .associate { it.key to it.value }

        val dailySeconds = current.dailySeconds.toMutableMap()
        if (seconds > 0) {
            dailySeconds[today] = (dailySeconds[today] ?: 0L) + seconds
        }
        val trimmedSeconds = dailySeconds.entries
            .sortedByDescending { it.key }
            .take(MAX_DAYS)
            .associate { it.key to it.value }

        val events = (current.events + PlayEvent(key, title, artist, artUrl, now, seconds))
            .takeLast(MAX_EVENTS)

        save(StatsData(tracks, trimmed, current.total + 1, events, trimmedSeconds))
    }

    fun clear() {
        save(StatsData())
    }

    /**
     * The in-memory state changes immediately; the JSON write happens off the main
     * thread, in order. A play should never serialise a few thousand events on the UI
     * thread, and two quick plays (or a reset followed by a play) must reach the disk
     * in the order they happened — a lock alone only guarantees they do not overlap.
     */
    private fun save(data: StatsData) {
        _state.value = data
        val snapshot = data
        writer.launch { writeToDisk(snapshot) }
    }

    private fun writeToDisk(data: StatsData) {
        val tracks = JSONArray()
        data.tracks.forEach { stat ->
            tracks.put(
                JSONObject().apply {
                    put("key", stat.key)
                    put("title", stat.title)
                    put("artist", stat.artist ?: JSONObject.NULL)
                    put("art", stat.artUrl ?: JSONObject.NULL)
                    put("plays", stat.plays)
                    put("last", stat.lastPlayed)
                }
            )
        }
        val daily = JSONObject()
        data.daily.forEach { (day, count) -> daily.put(day, count) }

        val dailySeconds = JSONObject()
        data.dailySeconds.forEach { (day, seconds) -> dailySeconds.put(day, seconds) }

        val events = JSONArray()
        data.events.forEach { event ->
            events.put(
                JSONObject().apply {
                    put("key", event.key)
                    put("title", event.title)
                    put("artist", event.artist ?: JSONObject.NULL)
                    put("art", event.artUrl ?: JSONObject.NULL)
                    put("at", event.at)
                    put("seconds", event.seconds)
                }
            )
        }

        prefs.edit()
            .putString(KEY_TRACKS, tracks.toString())
            .putString(KEY_DAILY, daily.toString())
            .putString(KEY_SECONDS, dailySeconds.toString())
            .putInt(KEY_TOTAL, data.total)
            .putString(KEY_EVENTS, events.toString())
            .apply()
    }

    private fun load(): StatsData {
        val tracks = runCatching {
            val array = JSONArray(prefs.getString(KEY_TRACKS, "[]"))
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val key = json.optString("key").takeIf { it.isNotBlank() } ?: continue
                    add(
                        TrackStat(
                            key = key,
                            title = json.optString("title"),
                            artist = json.optString("artist").takeIf { it.isNotBlank() },
                            artUrl = json.optString("art").takeIf { it.isNotBlank() },
                            plays = json.optInt("plays"),
                            lastPlayed = json.optLong("last"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())

        val daily = runCatching {
            val json = JSONObject(prefs.getString(KEY_DAILY, "{}"))
            buildMap { json.keys().forEach { key -> put(key, json.optInt(key)) } }
        }.getOrDefault(emptyMap())

        val dailySeconds = runCatching {
            val json = JSONObject(prefs.getString(KEY_SECONDS, "{}"))
            buildMap { json.keys().forEach { key -> put(key, json.optLong(key)) } }
        }.getOrDefault(emptyMap())

        val events = runCatching {
            val array = JSONArray(prefs.getString(KEY_EVENTS, "[]"))
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val key = json.optString("key").takeIf { it.isNotBlank() } ?: continue
                    add(
                        PlayEvent(
                            key = key,
                            title = json.optString("title"),
                            artist = json.optString("artist").takeIf { it.isNotBlank() },
                            artUrl = json.optString("art").takeIf { it.isNotBlank() },
                            at = json.optLong("at"),
                            seconds = json.optInt("seconds"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())

        return StatsData(
            tracks,
            daily,
            prefs.getInt(KEY_TOTAL, daily.values.sum()),
            events,
            dailySeconds,
        )
    }

    companion object {
        private const val PREFS = "arc_music_stats"
        private const val KEY_TRACKS = "tracks"
        private const val KEY_DAILY = "daily"
        private const val KEY_TOTAL = "total"
        private const val KEY_EVENTS = "events"
        private const val KEY_SECONDS = "dailySeconds"
        private const val MAX_TRACKS = 400
        private const val MAX_DAYS = 3_650
        // Each play rewrites this log. 4,000 events is several months of history and
        // keeps the per-play write around a few hundred kilobytes rather than megabytes.
        private const val MAX_EVENTS = 4_000

        @Volatile
        private var instance: StatsRepository? = null

        fun get(context: Context): StatsRepository =
            instance ?: synchronized(this) {
                instance ?: StatsRepository(context.applicationContext).also { instance = it }
            }
    }
}

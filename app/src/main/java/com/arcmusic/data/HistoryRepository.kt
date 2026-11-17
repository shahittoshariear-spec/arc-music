package com.arcmusic.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicLong

/**
 * Persists the list of finished conversions as a small JSON blob.
 *
 * Deliberately a process-wide singleton: [com.arcmusic.downloader.DownloadService]
 * is what writes new records while [com.arcmusic.ui.MainViewModel] renders them, and
 * separate instances would each hold their own [MutableStateFlow] — the list would look
 * empty until a restart, and a later delete would then write the stale list back over
 * the fresh record.
 */
class HistoryRepository private constructor(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _items = MutableStateFlow(decode(prefs.getString(KEY_ITEMS, null)))
    val items: StateFlow<List<DownloadRecord>> = _items.asStateFlow()

    /** Tag writes arrive one per song; only the last one needs to touch the disk. */
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var pendingWrite: Job? = null

    /**
     * Every mutation takes a number. A delayed write only survives if it is still the
     * newest one when it wakes: cancelling a job that has already resumed does nothing,
     * and its stale snapshot would otherwise land after a newer structural change and
     * bring a deleted song back.
     */
    private val writeVersion = AtomicLong()

    fun add(record: DownloadRecord) = mutate { current ->
        // One file, one row: a re-download of the same song lands on the same media
        // row, so replacing by uri keeps the list from showing it twice.
        listOf(record) + current.filterNot { it.id == record.id || it.uri == record.uri }
    }

    /**
     * Adds many at once. A 300-song playlist would otherwise re-serialise and rewrite
     * the whole blob once per song.
     */
    fun addAll(records: List<DownloadRecord>) {
        if (records.isEmpty()) return
        val ids = records.mapTo(mutableSetOf()) { it.id }
        val uris = records.mapTo(mutableSetOf()) { it.uri }
        mutate { current ->
            records + current.filterNot { it.id in ids || it.uri in uris }
        }
    }

    /** Replaces the stored entry with the same id, for example after its tags were rewritten. */
    fun update(record: DownloadRecord) = mutate(debounceWrite = true) { current ->
        current.map { if (it.id == record.id) record else it }
    }

    /**
     * Applies many replacements in one pass. A first-time import fills in covers for
     * hundreds of songs; updating them one at a time would republish the whole list on
     * every cover and re-sort the library each time.
     */
    fun updateAll(records: List<DownloadRecord>) {
        if (records.isEmpty()) return
        val byId = records.associateBy { it.id }
        mutate(debounceWrite = true) { current -> current.map { byId[it.id] ?: it } }
    }

    fun remove(id: String) = mutate { current -> current.filterNot { it.id == id } }

    /** Removes a selection in one write instead of one full rewrite per song. */
    fun removeAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val wanted = ids.toSet()
        mutate { current -> current.filterNot { it.id in wanted } }
    }

    fun clear() = mutate { emptyList() }

    private fun mutate(
        debounceWrite: Boolean = false,
        transform: (List<DownloadRecord>) -> List<DownloadRecord>,
    ) {
        synchronized(this) {
            val next = transform(_items.value)
            _items.value = next
            val version = writeVersion.incrementAndGet()
            pendingWrite?.cancel()
            pendingWrite = writes.launch {
                if (debounceWrite) delay(500)
                if (writeVersion.get() != version) return@launch
                // Encoding the whole library is the expensive half; it belongs on the
                // write thread, not on whichever caller happened to press delete.
                prefs.edit().putString(KEY_ITEMS, encode(next)).apply()
            }
        }
    }

    private fun encode(items: List<DownloadRecord>): String {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    private fun decode(raw: String?): List<DownloadRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        // One bad element must not cost the whole library: dropping it beats returning
        // an empty list that the next write would then persist over everything.
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching { DownloadRecord.fromJson(item) }.getOrNull()
        }
    }

    companion object {
        private const val PREFS = "yt_converter_history"
        private const val KEY_ITEMS = "items"

        @Volatile
        private var instance: HistoryRepository? = null

        fun get(context: Context): HistoryRepository =
            instance ?: synchronized(this) {
                instance ?: HistoryRepository(context.applicationContext).also { instance = it }
            }
    }
}

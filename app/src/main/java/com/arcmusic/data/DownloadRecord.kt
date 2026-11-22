package com.arcmusic.data

import org.json.JSONObject

/** One finished conversion, as shown in the history list. */
data class DownloadRecord(
    val id: String,
    val title: String,
    val uploader: String?,
    val thumbnailUrl: String?,
    val formatName: String,
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationSeconds: Long,
    val createdAt: Long,
    /** Tags written by the editor or Auto(rc), so the editor can reopen with them. */
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val trackNumber: String? = null,
    /**
     * When metadata was last written, by Auto(rc) or by hand. A background Auto(rc)
     * job queued before this moment is discarded, so a song is tagged once and a
     * manual edit is never overwritten by a late queue entry.
     */
    val metadataUpdatedAt: Long? = null,
    /**
     * The page this song was converted from, when it is known. Search results use it
     * to tell an already-downloaded song from one that is only available online.
     * Absent on records written before this field existed.
     */
    val sourceUrl: String? = null,
) {
    val format: AudioFormat
        get() = runCatching { AudioFormat.valueOf(formatName) }.getOrDefault(AudioFormat.ORIGINAL)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("uploader", uploader ?: JSONObject.NULL)
        put("thumbnailUrl", thumbnailUrl ?: JSONObject.NULL)
        put("format", formatName)
        put("uri", uri)
        put("fileName", fileName)
        put("mimeType", mimeType)
        put("sizeBytes", sizeBytes)
        put("durationSeconds", durationSeconds)
        put("createdAt", createdAt)
        put("album", album ?: JSONObject.NULL)
        put("albumArtist", albumArtist ?: JSONObject.NULL)
        put("genre", genre ?: JSONObject.NULL)
        put("year", year ?: JSONObject.NULL)
        put("trackNumber", trackNumber ?: JSONObject.NULL)
        put("metadataUpdatedAt", metadataUpdatedAt ?: JSONObject.NULL)
        put("sourceUrl", sourceUrl ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): DownloadRecord? {
            val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
            return DownloadRecord(
                id = id,
                title = json.optString("title", "Untitled"),
                uploader = json.optNullableString("uploader"),
                thumbnailUrl = json.optNullableString("thumbnailUrl"),
                formatName = json.optString("format", AudioFormat.ORIGINAL.name),
                uri = json.optString("uri"),
                fileName = json.optString("fileName"),
                mimeType = json.optString("mimeType", "audio/*"),
                sizeBytes = json.optLong("sizeBytes"),
                durationSeconds = json.optLong("durationSeconds"),
                createdAt = json.optLong("createdAt"),
                album = json.optNullableString("album"),
                albumArtist = json.optNullableString("albumArtist"),
                genre = json.optNullableString("genre"),
                year = json.optNullableString("year"),
                trackNumber = json.optNullableString("trackNumber"),
                metadataUpdatedAt = if (json.has("metadataUpdatedAt") && !json.isNull("metadataUpdatedAt")) {
                    json.optLong("metadataUpdatedAt")
                } else {
                    null
                },
                sourceUrl = json.optNullableString("sourceUrl"),
            )
        }

        private fun JSONObject.optNullableString(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}

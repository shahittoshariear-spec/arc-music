package com.arcmusic.downloader

/**
 * YouTube hands out the same frame at several resolutions and yt-dlp's search output
 * often picks the small one. The high-quality setting swaps the size segment in the URL;
 * services serve a missing variant as a 404, which Coil simply retries with its fallback
 * placeholder, so this is only applied when the user asked for it.
 */
object Thumbnails {

    fun highRes(url: String?): String? {
        if (url.isNullOrBlank()) return url
        if (!url.contains("ytimg.com") && !url.contains("youtube.com")) return url
        return url
            .replace("/mqdefault.", "/maxresdefault.")
            .replace("/hqdefault.", "/maxresdefault.")
            .replace("/sddefault.", "/maxresdefault.")
    }
}

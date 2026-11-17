package com.arcmusic.downloader

import android.content.Context
import android.webkit.CookieManager
import java.io.File

/**
 * Captures the YouTube session from the in-app browser as a Netscape cookie file, the
 * one form yt-dlp accepts.
 *
 * Without it, videos that ask you to sign in — private playlists, liked videos, age
 * gates, members-only — fail. The file lives in app-private storage and is only ever
 * read back by the bundled engine.
 */
object CookieStore {

    private const val FILE_NAME = "cookies.txt"

    /** Cookies read back from [CookieManager] carry no expiry, so give them a year. */
    private const val EXPIRY_SECONDS = 365L * 24 * 60 * 60

    /**
     * A Google sign-in spans two cookie domains: the YouTube session and the Google
     * account. Both are needed, and the same cookie comes back from several hosts.
     */
    private val SOURCES = listOf(
        "https://www.youtube.com" to ".youtube.com",
        "https://m.youtube.com" to ".youtube.com",
        "https://youtube.com" to ".youtube.com",
        "https://accounts.google.com" to ".google.com",
        "https://www.google.com" to ".google.com",
        "https://google.com" to ".google.com",
    )

    fun pathOrNull(context: Context): String? =
        file(context).takeIf { it.isFile && it.length() > 0L }?.absolutePath

    /**
     * Rewrites the cookie file from the current browser session. Must be called on the
     * main thread, because [CookieManager] is not thread-safe.
     */
    fun save(context: Context): Boolean {
        val manager = CookieManager.getInstance()
        val expiry = System.currentTimeMillis() / 1000L + EXPIRY_SECONDS
        val lines = mutableListOf("# Netscape HTTP Cookie File")
        val seen = mutableSetOf<String>()

        for ((url, domain) in SOURCES) {
            val raw = runCatching { manager.getCookie(url) }.getOrNull().orEmpty()
            for (pair in raw.split(';')) {
                val name = pair.substringBefore('=', "").trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) continue
                // The same cookie is returned by every host it applies to.
                if (!seen.add("$domain\t$name")) continue
                lines += listOf(domain, "TRUE", "/", "TRUE", expiry.toString(), name, value)
                    .joinToString("\t")
            }
        }

        if (lines.size == 1) return false

        return runCatching {
            val target = file(context)
            target.writeText(lines.joinToString("\n") + "\n")
            true
        }.getOrDefault(false)
    }

    /** True when a signed-in session is on file, so the engine can be pointed at it. */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}

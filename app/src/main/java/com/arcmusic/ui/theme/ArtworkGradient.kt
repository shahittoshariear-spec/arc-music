package com.arcmusic.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pulls two usable colours out of cover art for the gradient background.
 *
 * A full palette quantiser would be more precise than necessary here: the artwork is
 * shrunk to 24×24 and the pixels are averaged weighted by saturation, which gives the
 * one hue a listener actually sees. Results are cached by URL, so scrubbing through an
 * album costs one decode per cover.
 */
object ArtworkPalette {

    private const val CACHE_SIZE = 32
    private const val SAMPLE = 24
    private const val MAX_BYTES = 2_000_000

    private val cache = object : LinkedHashMap<String, List<Color>>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Color>>) =
            size > CACHE_SIZE
    }

    fun cached(url: String?): List<Color>? {
        val key = url?.takeIf { it.isNotBlank() } ?: return null
        return synchronized(cache) { cache[key] }
    }

    suspend fun colors(context: Context, url: String): List<Color>? = withContext(Dispatchers.IO) {
        cached(url)?.let { return@withContext it }

        val bytes = runCatching { read(context, url) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext null

        val decoded = runCatching {
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = 4 },
            )
        }.getOrNull() ?: return@withContext null

        val small = runCatching {
            Bitmap.createScaledBitmap(decoded, SAMPLE, SAMPLE, true)
        }.getOrDefault(decoded)

        val palette = extract(small)
        small.recycle()
        if (small !== decoded) decoded.recycle()

        palette?.let { synchronized(cache) { cache[url] = it } }
        palette
    }

    /**
     * Saturated mid-tones win; near-black and blown-out pixels say little about the
     * artwork's colour. Without any of them the average grey is used instead.
     */
    private fun extract(bitmap: Bitmap): List<Color>? {
        var weight = 0f
        var red = 0f
        var green = 0f
        var blue = 0f
        var greyWeight = 0f
        var greyRed = 0f
        var greyGreen = 0f
        var greyBlue = 0f

        val hsv = FloatArray(3)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha < 200) continue
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                android.graphics.Color.RGBToHSV(r, g, b, hsv)
                val saturation = hsv[1]
                val value = hsv[2]
                if (value in 0.12f..0.97f) {
                    greyWeight += 1f
                    greyRed += r
                    greyGreen += g
                    greyBlue += b
                }
                if (saturation in 0.22f..1f && value in 0.18f..0.96f) {
                    val w = saturation
                    weight += w
                    red += r * w
                    green += g * w
                    blue += b * w
                }
            }
        }

        if (weight > 0f) {
            val vibrant = Color(
                red = (red / weight / 255f).coerceIn(0f, 1f),
                green = (green / weight / 255f).coerceIn(0f, 1f),
                blue = (blue / weight / 255f).coerceIn(0f, 1f),
            )
            val deep = lerp(vibrant, Color.Black, 0.62f)
            return listOf(vibrant, deep)
        }

        if (greyWeight > 0f) {
            val grey = Color(
                red = (greyRed / greyWeight / 255f).coerceIn(0f, 1f),
                green = (greyGreen / greyWeight / 255f).coerceIn(0f, 1f),
                blue = (greyBlue / greyWeight / 255f).coerceIn(0f, 1f),
            )
            return listOf(grey, lerp(grey, Color.Black, 0.62f))
        }
        return null
    }

    private fun read(context: Context, url: String): ByteArray {
        if (url.startsWith("content://") || url.startsWith("file://")) {
            return context.contentResolver.openInputStream(Uri.parse(url))?.use {
                it.bounded(MAX_BYTES)
            } ?: ByteArray(0)
        }
        if (!url.startsWith("http")) {
            val file = File(url)
            if (file.isFile) return file.inputStream().use { it.bounded(MAX_BYTES) }
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("User-Agent", "ArcMusic/1.0")
        }
        return try {
            connection.inputStream.use { it.bounded(MAX_BYTES) }
        } finally {
            connection.disconnect()
        }
    }

    /** readNBytes is API 33; this keeps the same cap on older phones. */
    private fun java.io.InputStream.bounded(max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (total < max) {
            val read = read(chunk)
            if (read < 0) break
            output.write(chunk, 0, read)
            total += read
        }
        return output.toByteArray()
    }
}

/**
 * The two gradient colours for a track, or null when the feature is off or the artwork
 * has not been decoded yet. The blend with the surface colour happens at the call site.
 */
@Composable
fun rememberArtworkGradient(url: String?, enabled: Boolean): List<Color>? {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var colors by remember(url) { mutableStateOf(ArtworkPalette.cached(url)) }

    LaunchedEffect(url, enabled, lifecycleOwner) {
        if (!enabled || url.isNullOrBlank()) return@LaunchedEffect
        // Only fetch while the UI is on screen: a track change during background
        // playback should not wake the network just to tint a background nobody sees.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (colors == null) {
                colors = ArtworkPalette.cached(url) ?: ArtworkPalette.colors(context, url)
            }
        }
    }

    return if (enabled) colors else null
}

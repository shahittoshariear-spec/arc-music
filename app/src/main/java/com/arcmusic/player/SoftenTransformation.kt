package com.arcmusic.player

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation

/**
 * Cheap stand-in for `Modifier.blur` on the full-screen backdrops.
 *
 * A RenderEffect blur is re-evaluated by the GPU whenever its layer is drawn, which on a
 * full-screen image is one of the most expensive things the app can do. Decoding the
 * cover at a thumbnail size and box-blurring it once on a background thread instead
 * costs about a millisecond, and the bilinear upscale to full screen supplies the rest
 * of the softness for free.
 */
class SoftenTransformation(
    private val radius: Int = 4,
    private val passes: Int = 3,
) : Transformation() {

    override val cacheKey: String = "soften-$radius-$passes"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width
        val height = input.height
        if (width < 2 || height < 2) return input

        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)

        var source = pixels
        var target = IntArray(pixels.size)
        val rowBuffer = IntArray(pixels.size)
        repeat(passes.coerceAtLeast(1)) {
            blurRows(source, rowBuffer, width, height)
            blurColumns(rowBuffer, target, width, height)
            source = target
            target = IntArray(pixels.size)
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(source, 0, width, 0, 0, width, height)
        return output
    }

    private fun blurRows(source: IntArray, target: IntArray, width: Int, height: Int) {
        val span = radius.coerceAtMost(width - 1)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val from = (x - span).coerceAtLeast(0)
                val to = (x + span).coerceAtMost(width - 1)
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (i in from..to) {
                    val pixel = source[row + i]
                    a += pixel ushr 24 and 0xFF
                    r += pixel ushr 16 and 0xFF
                    g += pixel ushr 8 and 0xFF
                    b += pixel and 0xFF
                }
                val count = to - from + 1
                target[row + x] = (a / count shl 24) or
                    (r / count shl 16) or
                    (g / count shl 8) or
                    (b / count)
            }
        }
    }

    private fun blurColumns(source: IntArray, target: IntArray, width: Int, height: Int) {
        val span = radius.coerceAtMost(height - 1)
        for (x in 0 until width) {
            for (y in 0 until height) {
                val from = (y - span).coerceAtLeast(0)
                val to = (y + span).coerceAtMost(height - 1)
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (i in from..to) {
                    val pixel = source[i * width + x]
                    a += pixel ushr 24 and 0xFF
                    r += pixel ushr 16 and 0xFF
                    g += pixel ushr 8 and 0xFF
                    b += pixel and 0xFF
                }
                val count = to - from + 1
                target[y * width + x] = (a / count shl 24) or
                    (r / count shl 16) or
                    (g / count shl 8) or
                    (b / count)
            }
        }
    }
}

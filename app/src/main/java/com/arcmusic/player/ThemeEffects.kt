package com.arcmusic.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.arcmusic.data.AppTheme
import com.arcmusic.ui.theme.LocalAppTheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * The two special editions get a backdrop of their own. Everything here is drawn twice a
 * second at most and stops with the lifecycle: these screens sit under the whole app, so
 * an effect that ran at display refresh rate would be a constant battery cost.
 */
@Composable
fun ThemeBackdrop(animate: Boolean, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    if (!theme.isSpecial) return

    var phase by remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(theme, animate) {
        // The effects run only while the full player is open. On the library, browse and
        // search tabs the same shapes stay static: a full-screen canvas repainting in
        // the background of every tab is not worth a few drifting stars.
        if (!animate) {
            phase = 0f
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(PHASE_MS)
                phase = (phase + 0.11f) % (2f * PI.toFloat())
            }
        }
    }

    // The same effects need different inks in daylight: white stars vanish on a pale
    // sky, and the punched-out moon would read as a hole.
    val background = MaterialTheme.colorScheme.background
    val dark = background.luminance() < 0.5f
    Canvas(modifier.fillMaxSize()) {
        when (theme) {
            AppTheme.P3R -> drawP3R(phase, dark)
            AppTheme.CELESTIAL -> drawCww(phase, dark)
            else -> Unit
        }
    }
}

/** A static halo and ring for the special-edition logo. */
@Composable
fun SpecialLogoHalo(theme: AppTheme, modifier: Modifier = Modifier) {
    if (!theme.isSpecial) return
    Canvas(modifier.size(38.dp)) {
        val accent = if (theme == AppTheme.P3R) P3R_GREEN else CWW_CYAN
        drawCircle(
            brush = Brush.radialGradient(
                listOf(accent.copy(alpha = 0.30f), Color.Transparent),
            ),
            radius = size.minDimension * 0.85f,
        )
        drawArc(
            brush = Brush.sweepGradient(
                listOf(Color.Transparent, accent.copy(alpha = 0.65f), Color.Transparent),
            ),
            startAngle = 0f,
            sweepAngle = 190f,
            useCenter = false,
            topLeft = Offset(size.width * 0.04f, size.height * 0.04f),
            size = androidx.compose.ui.geometry.Size(
                size.width * 0.92f,
                size.height * 0.92f,
            ),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx()),
        )
    }
}

/**
 * A soft halo behind the full-screen artwork of a special edition. Static on purpose:
 * the rotating ring that used to live here read as a stray line around the cover.
 */
@Composable
fun SpecialArtworkGlow(modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    if (!theme.isSpecial) return
    val accent = if (theme == AppTheme.P3R) P3R_GREEN else CWW_CYAN
    val second = if (theme == AppTheme.P3R) P3R_CYAN else CWW_PERIWINKLE
    Canvas(modifier) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(accent.copy(alpha = 0.20f), second.copy(alpha = 0.07f), Color.Transparent),
            ),
            radius = size.minDimension * 0.72f,
        )
    }
}

/**
 * P3R: the diagonal print of the artwork, a slow scan beam and four corner brackets.
 * Lines stay under 6% alpha so titles keep their contrast.
 */
private fun DrawScope.drawP3R(phase: Float, dark: Boolean) {
    val width = size.width
    val height = size.height
    val ink = if (dark) P3R_GREEN else Color(0xFF0B5FC0)
    val step = 52.dp.toPx()
    val shift = (phase * 14f) % step
    var x = -height - step + shift
    while (x < width + height) {
        drawLine(
            color = ink.copy(alpha = if (dark) 0.045f else 0.055f),
            start = Offset(x, 0f),
            end = Offset(x + height, height),
            strokeWidth = 1.dp.toPx(),
        )
        x += step
    }

    // A single beam crossing the top third, green fading to cyan.
    val travel = (phase / (2f * PI.toFloat()))
    val beamY = height * (0.08f + 0.30f * travel)
    val half = 70.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                P3R_CYAN.copy(alpha = if (dark) 0.07f else 0.05f),
                P3R_GREEN.copy(alpha = if (dark) 0.05f else 0.04f),
                Color.Transparent,
            ),
            startY = beamY - half,
            endY = beamY + half,
        ),
        topLeft = Offset(0f, (beamY - half).coerceAtLeast(0f)),
        size = androidx.compose.ui.geometry.Size(width, half * 2f),
    )

    // Corner brackets, the detail that makes the edition read as deliberate.
    val inset = 10.dp.toPx()
    val arm = 26.dp.toPx()
    val stroke = 1.4.dp.toPx()
    val glow = P3R_GREEN.copy(alpha = 0.22f)
    listOf(
        Offset(inset, inset) to Offset(1f, 1f),
        Offset(width - inset, inset) to Offset(-1f, 1f),
        Offset(inset, height - inset) to Offset(1f, -1f),
        Offset(width - inset, height - inset) to Offset(-1f, -1f),
    ).forEach { (corner, dir) ->
        drawLine(glow, corner, corner + Offset(arm * dir.x, 0f), strokeWidth = stroke)
        drawLine(glow, corner, corner + Offset(0f, arm * dir.y), strokeWidth = stroke)
    }
}

/**
 * Celestial: a slow field of stars that fade in and out under two drifting nebulae.
 */
private fun DrawScope.drawCww(phase: Float, dark: Boolean) {
    val width = size.width
    val height = size.height

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(CWW_CYAN.copy(alpha = 0.075f), Color.Transparent),
            center = Offset(width * (0.22f + 0.05f * sin(phase)), height * 0.18f),
            radius = width * 0.8f,
        ),
        radius = width * 0.8f,
        center = Offset(width * (0.22f + 0.05f * sin(phase)), height * 0.18f),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(CWW_PERIWINKLE.copy(alpha = 0.06f), Color.Transparent),
            center = Offset(width * (0.85f - 0.04f * sin(phase)), height * 0.55f),
            radius = width * 0.75f,
        ),
        radius = width * 0.75f,
        center = Offset(width * (0.85f - 0.04f * sin(phase)), height * 0.55f),
    )

    // Deterministic star field: the same layout every launch, so it reads as a sky.
    val base = 1.1.dp.toPx()
    for (i in 0 until 34) {
        val fx = ((i * 37 + 11) % 100) / 100f
        val fy = ((i * 61 + 7) % 100) / 100f
        val twinkle = 0.35f + 0.65f * ((sin(phase * 1.4f + i * 1.7f) + 1f) / 2f)
        val radius = base * (0.6f + (i % 4) * 0.28f) * twinkle
        val color = when {
            i % 11 == 0 -> CWW_GOLD
            i % 5 == 0 -> CWW_PERIWINKLE
            else -> if (dark) Color.White else Color(0xFF1C3350)
        }
        val alpha = if (dark) 0.20f + 0.55f * twinkle else 0.10f + 0.28f * twinkle
        drawCircle(color = color.copy(alpha = alpha), radius = radius, center = Offset(width * fx, height * fy * 0.9f))
    }
}

private const val PHASE_MS = 120L

private val P3R_GREEN = Color(0xFF59F9A6)
private val P3R_CYAN = Color(0xFF9DFCFC)
private val CWW_CYAN = Color(0xFF6FE3F2)
private val CWW_PERIWINKLE = Color(0xFF8FA6FF)
private val CWW_GOLD = Color(0xFFE4C79A)

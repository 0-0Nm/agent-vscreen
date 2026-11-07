package com.kilon.agentvscreen.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kilon.agentvscreen.core.BackgroundOperation

/** Who has the background screen right now, as the user sees it (drives colours, buttons and words). */
enum class Mode { ACTING, STANDBY, IDLE, TAKEOVER, STOPPED }

fun modeOf(s: BackgroundOperation.State): Mode = when {
    s.userStopped -> Mode.STOPPED
    s.takeoverSessionId != null && s.takeoverSessionId == s.ownerSessionId -> Mode.TAKEOVER
    s.operating -> Mode.ACTING
    s.idle || s.powerSaving -> Mode.IDLE
    else -> Mode.STANDBY
}

object Palette {
    val rainbow = listOf(
        Color(0xFFFF5F6D), Color(0xFFFFB347), Color(0xFFFFE66D), Color(0xFF4ADE80),
        Color(0xFF22D3EE), Color(0xFF6C7BFF), Color(0xFFC06CFF), Color(0xFFFF5F6D),
    )
    val standby = Color(0xFF7CC8FF)
    val idle = Color(0xFF94A3B8)
    val takeover = Color(0xFF34D399)
    val stopped = Color(0xFFFF5A5F)
    val brandA = Color(0xFF6C5CE7)
    val brandB = Color(0xFF00C2FF)
    val ink = Color(0xFF0E1220)

    fun of(m: Mode): Color = when (m) {
        Mode.ACTING -> Color(0xFF8B7CFF); Mode.STANDBY -> standby; Mode.IDLE -> idle
        Mode.TAKEOVER -> takeover; Mode.STOPPED -> stopped
    }

    fun word(m: Mode): String = when (m) {
        Mode.ACTING -> "AI 操作中"; Mode.STANDBY -> "AI 待命"; Mode.IDLE -> "空闲"
        Mode.TAKEOVER -> "你在操作"; Mode.STOPPED -> "已停止 AI"
    }
}

/** A sweep gradient turned by [deg] (the rainbow runs around the frame). */
private class TurningSweep(val colors: List<Color>, val deg: Float) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val cx = size.width / 2f; val cy = size.height / 2f
        return android.graphics.SweepGradient(cx, cy, colors.map { it.toArgb() }.toIntArray(), null).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setRotate(deg, cx, cy) })
        }
    }
}

/**
 * The breathing border around the background screen, drawn OUTSIDE the content (give the element [pad] of room):
 * a turning rainbow while the AI acts, a calm blue breath while it waits, green while the user drives, red when the
 * user stopped it, grey when idle. A soft glow of the same colours sits under the line.
 */
/** False only in layout snapshots (an infinite animation never lets a test clock settle). */
@Volatile var animateLook = true

@Composable
fun Modifier.glowBorder(mode: Mode, corner: Dp, width: Dp = 2.5.dp, pad: Dp = 6.dp): Modifier {
    var turn = 30f; var breath = 0.8f
    if (animateLook) {
        val t = rememberInfiniteTransition(label = "glow")
        turn = t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (mode == Mode.ACTING) 2400 else 12000, easing = LinearEasing)), label = "turn").value
        breath = t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (mode == Mode.ACTING) 900 else 1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath").value
    }
    return this.drawBehind {
        val stroke = width.toPx()
        val r = corner.toPx()
        val brush: Brush = if (mode == Mode.ACTING) TurningSweep(Palette.rainbow, turn) else SolidColor(Palette.of(mode))
        val glowMax = pad.toPx()
        val strength = when (mode) { Mode.IDLE -> 0.10f; Mode.ACTING -> 0.30f; else -> 0.22f } * (0.55f + 0.45f * breath)
        for (i in 4 downTo 1) {
            val grow = glowMax * i / 4f
            drawRoundRect(
                brush = brush, topLeft = Offset(-grow / 2, -grow / 2), size = Size(size.width + grow, size.height + grow),
                cornerRadius = CornerRadius(r + grow / 2), alpha = strength / i, style = Stroke(stroke + grow),
            )
        }
        val lineAlpha = if (mode == Mode.ACTING) 1f else 0.65f + 0.35f * breath
        drawRoundRect(brush = brush, topLeft = Offset(-stroke / 2, -stroke / 2), size = Size(size.width + stroke, size.height + stroke),
            cornerRadius = CornerRadius(r + stroke / 2), alpha = lineAlpha, style = Stroke(stroke))
    }
}

/** The app's colours: brand violet → cyan, quiet surfaces. */
@Composable
fun AvsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme: ColorScheme = if (dark) darkColorScheme(
        primary = Color(0xFFA99DFF), onPrimary = Color(0xFF1B1446), primaryContainer = Color(0xFF2E2770), onPrimaryContainer = Color(0xFFE4DFFF),
        secondary = Color(0xFF6FD8FF), tertiary = Palette.takeover,
        background = Color(0xFF0E1018), surface = Color(0xFF0E1018), surfaceContainer = Color(0xFF181B26),
        surfaceContainerHigh = Color(0xFF1F2330), surfaceContainerLow = Color(0xFF141722), outlineVariant = Color(0xFF2B3040),
    ) else lightColorScheme(
        primary = Color(0xFF5B4BDB), onPrimary = Color.White, primaryContainer = Color(0xFFE7E3FF), onPrimaryContainer = Color(0xFF1E1466),
        secondary = Color(0xFF0091C2), tertiary = Color(0xFF0E9F6E),
        background = Color(0xFFF6F7FB), surface = Color(0xFFF6F7FB), surfaceContainer = Color.White,
        surfaceContainerHigh = Color(0xFFF0F1F7), surfaceContainerLow = Color(0xFFFAFBFE), outlineVariant = Color(0xFFE3E5EE),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

package com.kilon.agentvscreen.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.kilon.agentvscreen.R
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenShell
import kotlin.math.roundToInt


/** [vscreen] The live picture of the background screen (floating window and full screen). */
@Composable
fun VScreenLiveFrame(sessionId: String, modifier: Modifier = Modifier, interactive: Boolean = false) {
    // Follow the session's CURRENT screen. Before, the frame only recomposed on the old screen's
    // frame counter: after a rebuild (orientation / restart) the window kept the released screen
    // and showed "loading" or a stale picture forever, while the agent worked on the new one.
    val st = BackgroundOperation.state.collectAsState().value
    var tick by remember { mutableStateOf(0) }
    val display = remember(sessionId, st.displayId, st.ownerSessionId, tick) { BackgroundOperation.previewDisplayFor(sessionId) }
    LaunchedEffect(sessionId, display) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            if (BackgroundOperation.previewDisplayFor(sessionId) !== display) { tick++; break }
        }
    }
    if (display == null) {
        Box(modifier.background(Color(0xFFF2F2F7)), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.vscreen_no_preview), fontSize = 11.sp, color = Color.Gray)
        }
        return
    }
    // Recompose on each new frame while this composable is on screen.
    val version = display.frameVersion.collectAsState().value
    val frame = remember(display, version) { display.latestFrame() }
    // A frame that is one flat colour (app gone, page not drawn yet) reads as a broken white box.
    val blank = remember(frame) { frame != null && !frame.isRecycled && isBlank(frame) }
    // v40: a blank frame with no app on the screen is the normal idle state, not a capture problem.
    val noApp = androidx.compose.runtime.produceState(false, blank, display) {
        value = blank && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.kilon.agentvscreen.core.VScreenShell.topActivity(display.displayId) == null }.getOrDefault(false)
        }
    }.value
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier
            .background(Color.White)
            .onSizeChanged { viewSize = it }
            .then(
                if (!interactive) Modifier
                else Modifier
                    .pointerInput(sessionId, display.generation) {
                        detectTapGestures(
                            onLongPress = { p ->
                                val (x, y) = map(p, viewSize, display.width, display.height)
                                vscreenUserInput(sessionId) { VScreenShell.longPress(it, x, y, 650) }
                            },
                        ) { p ->
                            val (x, y) = map(p, viewSize, display.width, display.height)
                            BackgroundOperation.userTap(sessionId, x, y)
                        }
                    }
                    .pointerInput(sessionId, display.generation, "drag") {
                        var start = Offset.Zero
                        var end = Offset.Zero
                        var startedAt = 0L
                        detectDragGestures(
                            onDragStart = { start = it; end = it; startedAt = System.currentTimeMillis() },
                            onDrag = { change, delta -> change.consume(); end += delta },
                            onDragEnd = {
                                val (x1, y1) = map(start, viewSize, display.width, display.height)
                                val (x2, y2) = map(end, viewSize, display.width, display.height)
                                val ms = (System.currentTimeMillis() - startedAt).toInt().coerceIn(80, 1500)
                                BackgroundOperation.userSwipe(sessionId, x1, y1, x2, y2, ms)
                            },
                        )
                    },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null && !frame.isRecycled) {
            // Always draw the real frame (a white loading page with a little text is content, and
            // a coarse sample used to replace it with "blank"); only label a truly flat frame.
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            if (blank) Text(stringResource(if (noApp) R.string.vscreen_no_app else R.string.vscreen_blank_frame), fontSize = 9.sp, color = Color.Gray,
                modifier = Modifier.align(Alignment.BottomCenter).background(Color(0xCCFFFFFF)).padding(horizontal = 6.dp, vertical = 2.dp))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.vscreen_loading), fontSize = 9.sp, color = Color.Gray)
            }
        }
    }
}

/** True when a 47x47 sample of the frame is all (almost) the same colour. */
private fun isBlank(b: android.graphics.Bitmap): Boolean = runCatching {
    val first = b.getPixel(b.width / 2, b.height / 2)
    for (i in 1..47) for (j in 1..47) {
        val c = b.getPixel((b.width * i / 48).coerceAtMost(b.width - 1), (b.height * j / 48).coerceAtMost(b.height - 1))
        val d = kotlin.math.abs(((c shr 16) and 0xff) - ((first shr 16) and 0xff)) +
            kotlin.math.abs(((c shr 8) and 0xff) - ((first shr 8) and 0xff)) + kotlin.math.abs((c and 0xff) - (first and 0xff))
        if (d > 24) return@runCatching false
    }
    true
}.getOrDefault(false)

/** roundToInt() that cannot throw: NaN / ∞ become 0 instead of crashing the app. */
private fun Float.safePx(): Int = if (isFinite()) roundToInt() else 0

private fun map(p: Offset, view: IntSize, w: Int, h: Int): Pair<Int, Int> {
    if (view.width == 0 || view.height == 0 || w <= 0 || h <= 0 || !p.x.isFinite() || !p.y.isFinite()) return 0 to 0
    val x = (p.x / view.width * w).roundToInt().coerceIn(0, w - 1)
    val y = (p.y / view.height * h).roundToInt().coerceIn(0, h - 1)
    return x to y
}


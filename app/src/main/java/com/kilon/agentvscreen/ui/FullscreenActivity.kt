package com.kilon.agentvscreen.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kilon.agentvscreen.api.Turns
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenHandoff
import com.kilon.agentvscreen.core.VScreenShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The background screen full screen, as a native page: edge-to-edge and immersive, the picture fitted with the same
 * breathing border as the floating window. Hold the picture 1 s to take over; then taps, long presses and swipes go
 * to the background screen, and the bottom bar gives back / home / typing / enter. Stop, hand back and "move this
 * app to my screen" are one tap away.
 */
class FullscreenActivity : ComponentActivity() {
    // The floating window shows the same picture: it steps aside while this page is in front.
    override fun onStart() { super.onStart(); Overlay.fullscreenShown(true) }
    override fun onStop() { Overlay.fullscreenShown(false); super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            AvsTheme {
                val s by BackgroundOperation.state.collectAsState()
                val session = s.ownerSessionId
                LaunchedEffect(session, s.displayId) { if (session == null || s.displayId == null) finish() }
                if (session != null) FullScreen(session)
            }
        }
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    @Composable
    private fun FullScreen(session: String) {
        val s by BackgroundOperation.state.collectAsState()
        val mode = modeOf(s)
        val takenOver = mode == Mode.TAKEOVER
        var typing by remember { mutableStateOf(false) }
        var text by remember { mutableStateOf("") }
        var hold by remember { mutableFloatStateOf(0f) }
        var holdAt by remember { mutableStateOf(Offset.Zero) }
        var hintAt by remember { mutableLongStateOf(0L) }
        var appLabel by remember { mutableStateOf<String?>(null) }
        var left by remember { mutableLongStateOf(-1L) }
        LaunchedEffect(s.displayId, s.operating) {
            val id = s.displayId ?: return@LaunchedEffect
            appLabel = withContext(Dispatchers.IO) {
                runCatching { VScreenShell.topPackage(id) }.getOrNull()?.let { pkg ->
                    runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                }
            }
        }
        LaunchedEffect(session) { while (true) { left = Turns.leftMs(session) ?: -1L; delay(1000) } }
        LaunchedEffect(takenOver) { if (!takenOver) typing = false }

        Box(Modifier.fillMaxSize().background(Color(0xFF07080D))) {
            // The picture, fitted, with its border.
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 56.dp, bottom = if (typing) 140.dp else 96.dp, start = 14.dp, end = 14.dp),
                contentAlignment = Alignment.Center) {
                val aspect = Overlay.aspect()
                val boxAspect = maxWidth / maxHeight
                val pw = if (aspect > boxAspect) maxWidth else maxHeight * aspect
                val ph = pw / aspect
                Box(Modifier.size(pw, ph).glowBorder(mode, 22.dp, width = 3.dp, pad = 10.dp)) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(Palette.ink)
                        .pointerInput(session, takenOver) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val t0 = SystemClock.uptimeMillis()
                                var end = down.position
                                var moved = false
                                var held = false
                                while (true) {
                                    val ev = withTimeoutOrNull(16L) { awaitPointerEvent() }
                                    val elapsed = SystemClock.uptimeMillis() - t0
                                    if (!takenOver && !moved && !held) {
                                        hold = ((elapsed - 150L) / 850f).coerceIn(0f, 1f); holdAt = down.position
                                        if (hold >= 1f) {
                                            held = true; hold = 0f
                                            if (s.userStopped) BackgroundOperation.userResume()
                                            BackgroundOperation.beginTakeover(session)
                                        }
                                    }
                                    if (ev == null) continue
                                    val ch = ev.changes.firstOrNull() ?: break
                                    end = ch.position
                                    if (hypot(end.x - down.position.x, end.y - down.position.y) > viewConfiguration.touchSlop) { moved = true; hold = 0f }
                                    if (takenOver) ch.consume()
                                    if (!ch.pressed) break
                                }
                                hold = 0f
                                val ms = SystemClock.uptimeMillis() - t0
                                if (takenOver) {
                                    val a = map(down.position, size.width.toFloat(), size.height.toFloat())
                                    val b = map(end, size.width.toFloat(), size.height.toFloat())
                                    if (a != null && b != null) when {
                                        moved -> BackgroundOperation.userSwipe(session, a.first, a.second, b.first, b.second, ms.toInt().coerceIn(80, 1500))
                                        ms >= 450L -> vscreenUserInput(session) { VScreenShell.longPress(it, a.first, a.second, ms.toInt().coerceAtMost(2000)) }
                                        else -> BackgroundOperation.userTap(session, a.first, a.second)
                                    }
                                } else if (!held && !moved) hintAt = SystemClock.uptimeMillis()
                            }
                        }) {
                        VScreenLiveFrame(session, Modifier.fillMaxSize(), interactive = false)
                        if (hold > 0f) Canvas(Modifier.fillMaxSize()) {
                            val r = 40.dp.toPx()
                            drawCircle(Color(0x66000000), r + 8f, holdAt)
                            drawArc(Palette.takeover, -90f, 360f * hold, false, topLeft = Offset(holdAt.x - r, holdAt.y - r), size = Size(r * 2, r * 2),
                                style = Stroke(width = 8f, cap = StrokeCap.Round))
                        }
                    }
                }
            }

            // Top bar.
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Circle(Icons.Filled.CloseFullscreen, "退出全屏", Color(0x33FFFFFF)) { finish() }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(appLabel ?: "后台副屏", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (mode == Mode.ACTING) Brush.sweepGradient(Palette.rainbow) else SolidColor(Palette.of(mode))))
                        Spacer(Modifier.width(5.dp))
                        val word = s.agentTitle ?: (Palette.word(mode) + if (mode == Mode.STANDBY && left >= 0) " · ${left / 60000}:${"%02d".format((left / 1000) % 60)} 后省电" else "")
                        Text(word, color = Color(0xCCFFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                when (mode) {
                    Mode.ACTING, Mode.STANDBY -> Pill("停止 AI", Icons.Filled.Stop, Palette.stopped, Color.White) { BackgroundOperation.userStop() }
                    else -> Circle(Icons.Filled.Close, "关闭副屏", Color(0x33FFFFFF)) {
                        BackgroundOperation.releaseNow(session); finish()
                    }
                }
            }

            // Hint.
            var now by remember { mutableLongStateOf(0L) }
            LaunchedEffect(hintAt) { now = SystemClock.uptimeMillis(); delay(2600); now = SystemClock.uptimeMillis() }
            AnimatedVisibility(now - hintAt < 2500 && hintAt > 0, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
                Text("长按画面 1 秒接管，接管后点、长按、滑动都会落到副屏上", color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xE612141C)).padding(horizontal = 14.dp, vertical = 8.dp))
            }

            // Bottom bar.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().navigationBarsPadding().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (typing) {
                    val fr = remember { FocusRequester() }
                    LaunchedEffect(Unit) { fr.requestFocus() }
                    fun send() {
                        val t = text.trim(); if (t.isEmpty()) return
                        text = ""
                        vscreenUserInput(session) { id ->
                            val r = runCatching { BackgroundOperation.typeText(id, t, append = true) }
                            r.exceptionOrNull()?.let { toast("没输进去：${it.message?.take(60)}") }
                        }
                    }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF1B1E2A)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (text.isEmpty()) Text("输入到副屏里有焦点的输入框", color = Color(0x88FFFFFF), fontSize = 15.sp)
                            BasicTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(fr), singleLine = true,
                                textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(Palette.standby),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
                        }
                        Circle(Icons.AutoMirrored.Filled.Send, "发送", Palette.brandA) { send() }
                    }
                }
                Row(Modifier.clip(RoundedCornerShape(30.dp)).background(Color(0xE612141C)).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (takenOver) {
                        Circle(Icons.AutoMirrored.Filled.ArrowBack, "返回", Color(0x22FFFFFF)) { vscreenUserInput(session) { VScreenShell.key(it, "back") } }
                        Circle(Icons.Filled.Home, "桌面", Color(0x22FFFFFF)) { vscreenUserInput(session) { VScreenShell.key(it, "home") } }
                        Circle(Icons.Filled.Keyboard, "打字", if (typing) Palette.brandA else Color(0x22FFFFFF)) { typing = !typing }
                        Circle(Icons.AutoMirrored.Filled.KeyboardReturn, "回车", Color(0x22FFFFFF)) { vscreenUserInput(session) { VScreenShell.key(it, "enter") } }
                        Pill("交还 AI", Icons.Filled.SmartToy, Palette.takeover, Color(0xFF052E1C)) { BackgroundOperation.userResume() }
                    } else {
                        if (mode == Mode.STOPPED) Pill("交还 AI", Icons.Filled.SmartToy, Palette.takeover, Color(0xFF052E1C)) { BackgroundOperation.userResume() }
                        Pill("接管", Icons.Filled.TouchApp, Color(0x22FFFFFF), Color.White) {
                            if (s.userStopped) BackgroundOperation.userResume()
                            BackgroundOperation.beginTakeover(session)
                        }
                        Pill("移到主屏", Icons.Filled.PhoneAndroid, Color(0x22FFFFFF), Color.White) {
                            Thread {
                                val (ok, msg) = runCatching { VScreenHandoff.userToMain(session) }.getOrElse { false to (it.message ?: "失败") }
                                toast(msg); if (ok) runOnUiThread { finish() }
                            }.start()
                        }
                    }
                }
            }
        }
    }

    private fun androidx.compose.ui.input.pointer.PointerInputScope.map(p: Offset, w: Float, h: Float): Pair<Int, Int>? {
        val r = android.graphics.RectF(0f, 0f, w, h)
        return pictureToScreen(p.x.coerceIn(0f, w - 1), p.y.coerceIn(0f, h - 1), r)
    }
}

@Composable
private fun Circle(icon: ImageVector, desc: String, bg: Color, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Pill(label: String, icon: ImageVector, bg: Color, fg: Color, onClick: () -> Unit) {
    Row(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Runs [op] with the display id off the main thread, only while [sessionId] holds the screen (接管). */
internal fun vscreenUserInput(sessionId: String, op: (Int) -> Unit) {
    val s = BackgroundOperation.state.value
    val id = s.displayId ?: return
    if (s.takeoverSessionId != sessionId) return
    Thread { runCatching { op(id) } }.apply { isDaemon = true; name = "vscreen-user-input" }.start()
}

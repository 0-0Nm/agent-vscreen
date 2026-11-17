package com.kilon.agentvscreen.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kilon.agentvscreen.cmd.LayoutInspector
import com.kilon.agentvscreen.cmd.LayoutInspectorPolicy
import com.kilon.agentvscreen.cmd.LayoutSnapshot
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.core.VScreenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Deliberately separate from LiveFrame gesture handlers: selecting here never injects input. */
@Composable
internal fun LayoutInspectorPanel(session: String, onClose: () -> Unit) {
    var snapshot by remember(session) { mutableStateOf<LayoutSnapshot?>(null) }
    var message by remember { mutableStateOf("采集中…") }
    var refresh by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Int?>(null) }
    var collapsed by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val state by BackgroundOperation.state.collectAsState()
    LaunchedEffect(session, refresh) {
        snapshot = null; selected = null; collapsed = emptySet(); busy = true
        val result = withContext(Dispatchers.IO) { runCatching { LayoutInspector.capture(session) } }
        snapshot = result.getOrNull()
        message = result.fold({ if (it.data.optBoolean("available")) "只读检查 · 点框只选中，不点击应用" else "不可用：${it.data.optString("reason")}" },
            { "采集未完成：${it.javaClass.simpleName}" })
        busy = false
    }
    // Reject stale ownership/state immediately; periodically check the target package without waking the display.
    val s = snapshot
    val owns = s == null || (state.displayId == s.data.optInt("display_id") && state.ownerSessionId == session &&
        !state.userStopped && state.takeoverSessionId == null && BackgroundOperation.previewDisplayFor(session)?.generation == s.data.optLong("generation"))
    LaunchedEffect(snapshot, owns) {
        if (!owns) { snapshot = null; selected = null; message = "副屏或会话状态改变，请刷新"; return@LaunchedEffect }
        val captured = snapshot ?: return@LaunchedEffect
        while (true) {
            delay(750)
            val pkg = captured.data.optString("targetPackage")
            val valid = withContext(Dispatchers.IO) {
                runCatching {
                    val display = BackgroundOperation.previewDisplayFor(session)
                    display != null && !display.released && display.generation == captured.data.optLong("generation") &&
                        display.width == captured.data.optInt("width") && display.height == captured.data.optInt("height") &&
                        display.inputWidth == captured.data.optInt("input_width") && display.inputHeight == captured.data.optInt("input_height") &&
                        display.rotation * 90 == captured.data.optInt("rotation") && display.bufferTurn == captured.data.optInt("frame_turn") &&
                        (captured.data.isNull("frame_version") || display.frameVersion.value == captured.data.optLong("frame_version")) &&
                        VScreenShell.topPackage(display.displayId) == pkg &&
                        VScreenShell.topActivity(display.displayId) == captured.data.optString("activity").takeIf { it.isNotEmpty() && it != "null" } &&
                        VScreenStore.ruleFor(pkg) != VScreenStore.Rule.BLOCK
                }.getOrDefault(false)
            }
            if (!valid) { snapshot = null; selected = null; message = "目标页面或应用限制改变，请刷新"; break }
        }
    }
    val visible = if (owns) snapshot else null
    val boxes = remember(visible) { visible?.boxes().orEmpty() }
    val rows = remember(boxes, collapsed, query) { LayoutInspectorPolicy.visibleRows(boxes, collapsed, query) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF10151E)).statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("副屏布局检查器", color = Color.White, modifier = Modifier.weight(1f), fontSize = 18.sp)
                TextButton(onClick = { if (!busy) refresh++ }, enabled = !busy) { Text(if (busy) "采集中" else "刷新") }
                TextButton(onClick = onClose) { Text("关闭") }
            }
            Text(message, color = Color(0xFF93C5FD), fontSize = 12.sp)
            if (visible != null) {
                val data = visible.data
                Text("副屏 ${data.optInt("display_id")} · 第 ${data.optLong("generation")} 代 · ${data.optInt("nodeCount")} 节点${if (data.optBoolean("truncated")) "（已截断）" else ""}", color = Color.LightGray, fontSize = 12.sp)
                Text("${data.optString("alignment")} · 图龄 ${data.optLong("frame_age_ms")}ms · 非原子快照", color = Color(0xFFFBBF24), fontSize = 11.sp)
                Box(Modifier.fillMaxWidth().weight(0.9f).background(Color.Black), contentAlignment = Alignment.Center) {
                    val bitmap = visible.bitmap
                    if (bitmap == null) Text("截图未提供（敏感节点／截断／无图／混合窗口）\n仍可查看脱敏布局树", color = Color.Gray)
                    else BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val ratio = bitmap.width.toFloat() / bitmap.height
                        val pw = minOf(maxWidth, maxHeight * ratio)
                        val ph = pw / ratio
                        val enabled = data.optBoolean("boxes_enabled")
                        Box(Modifier.size(pw, ph)) {
                            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                            if (enabled) Canvas(Modifier.fillMaxSize().pointerInput(visible) {
                                detectTapGestures { point ->
                                    selected = LayoutInspectorPolicy.hit(boxes, point.x / size.width * bitmap.width, point.y / size.height * bitmap.height)
                                }
                            }) {
                                val sx = size.width / bitmap.width; val sy = size.height / bitmap.height
                                boxes.filter { it.visible && it.area > 0 }.forEach { n ->
                                    val l = n.left.coerceIn(0, bitmap.width); val t = n.top.coerceIn(0, bitmap.height)
                                    val r = n.right.coerceIn(l, bitmap.width); val b = n.bottom.coerceIn(t, bitmap.height)
                                    drawRect(if (n.id == selected) Color.Yellow else Color(0x9960A5FA), Offset(l * sx, t * sy), Size((r-l) * sx, (b-t) * sy), style = Stroke(if (n.id == selected) 4f else 1f))
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), singleLine = true, label = { Text("搜索文字 / ID / 类名") })
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(rows, key = { it.id }) { n ->
                        val child = boxes.any { it.parent == n.id }
                        Row(Modifier.fillMaxWidth().background(if (selected == n.id) Color(0xFF263B54) else Color.Transparent).padding(start = (minOf(n.depth, 10) * 10).dp), verticalAlignment = Alignment.CenterVertically) {
                            if (child) TextButton(onClick = { collapsed = if (n.id in collapsed) collapsed - n.id else collapsed + n.id }) { Text(if (n.id in collapsed) "+" else "−") }
                            Text("#${n.id} ${n.label.ifBlank { "（无文字节点）" }}", color = Color.White, fontSize = 12.sp, maxLines = 2,
                                modifier = Modifier.weight(1f).clickable { selected = n.id }.padding(vertical = 8.dp))
                        }
                    }
                }
                val a = data.optJSONArray("nodes")
                val node = selected?.let { id -> (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }.firstOrNull { it.getInt("id") == id } }
                if (node != null) Text(node.toString(2), color = Color(0xFFBBF7D0), fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()).background(Color(0xFF17232B)).padding(8.dp))
                Text("冻结检查快照，不跟随实时页面。截图过旧或坐标不符时禁用标框。", color = Color.Gray, fontSize = 10.sp)
            } else Spacer(Modifier.weight(1f))
        }
    }
}

package com.kilon.agentvscreen.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kilon.agentvscreen.cmd.InspectorProposals
import com.kilon.agentvscreen.cmd.LayoutInspector
import com.kilon.agentvscreen.cmd.LayoutInspectorPolicy
import com.kilon.agentvscreen.cmd.LayoutInspectorPresentation
import com.kilon.agentvscreen.cmd.LayoutInspectorViewport
import com.kilon.agentvscreen.cmd.LayoutSnapshot
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.InspectorViewTicket
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.core.VScreenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** Full-screen, local-only inspector. The canvas owns the whole preview; tools are overlays. */
@Composable
internal fun LayoutInspectorPanel(lifecycle: Lifecycle, onClose: () -> Unit) {
    var ticket by remember { mutableStateOf<InspectorViewTicket?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<LayoutSnapshot?>(null) }
    var status by remember { mutableStateOf("等待采集") }
    var selectedId by remember { mutableStateOf<Int?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var toolOpen by remember { mutableStateOf(false) }
    var treeOpen by remember { mutableStateOf(false) }
    var technicalOpen by remember { mutableStateOf(false) }
    var onlyClickable by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var collapsed by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var requestSerial by remember { mutableIntStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    var toolOffset by remember { mutableStateOf(Offset.Zero) }
    val backgroundState by BackgroundOperation.state.collectAsState()
    val proposalState by InspectorProposals.state.collectAsState()
    val proposal = proposalState?.takeIf { it.displayId == ticket?.displayId && it.generation == ticket?.generation }
    var toolSize by remember { mutableStateOf(IntSize.Zero) }
    var reviewBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current

    fun closeOnce() {
        ticket?.let { BackgroundOperation.endInspectorView(it) }
        ticket = null
        requestSerial++
        onClose()
    }

    DisposableEffect(lifecycle) {
        fun begin() {
            if (ticket != null) return
            ticket = runCatching { BackgroundOperation.beginInspectorView() }.getOrNull()
            if (ticket == null) status = "副屏未打开，无法检查"
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> begin()
                Lifecycle.Event.ON_STOP -> {
                    ticket?.let { BackgroundOperation.endInspectorView(it) }
                    ticket = null
                    snapshot = null
                    selectedId = null
                    requestSerial++
                    capturing = false
                    status = "页面已暂停"
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) begin()
        onDispose {
            lifecycle.removeObserver(observer)
            ticket?.let { BackgroundOperation.endInspectorView(it) }
            ticket = null
        }
    }

    LaunchedEffect(ticket, refresh) {
        val current = ticket ?: return@LaunchedEffect
        val serial = ++requestSerial
        capturing = true
        snapshot = null
        selectedId = null
        collapsed = emptySet()
        zoom = 1f
        pan = Offset.Zero
        status = "正在采集…"
        val result = withContext(Dispatchers.IO) {
            runCatching {
                coroutineContext.ensureActive()
                BackgroundOperation.prepareInspectorView(current)
                coroutineContext.ensureActive()
                if (!BackgroundOperation.isInspectorViewValid(current)) error("DISPLAY_CLOSED")
                LayoutInspector.captureLocal(current, maxNodes = 200, maxDepth = 20)
            }
        }
        coroutineContext.ensureActive()
        if (serial != requestSerial) return@LaunchedEffect
        capturing = false
        if (ticket !== current || !BackgroundOperation.isInspectorViewValid(current)) {
            snapshot = null
            status = "副屏已关闭"
            return@LaunchedEffect
        }
        result.fold(
            { snap -> snapshot = snap; status = LayoutInspectorPresentation.status(snap.data.optString("alignment").ifBlank { if (snap.data.optBoolean("available", true)) "READY" else snap.data.optString("reason") }) },
            { error -> snapshot = null; status = LayoutInspectorPresentation.status(error.message ?: error.javaClass.simpleName) },
        )
    }

    val validView = ticket?.let { BackgroundOperation.isInspectorViewValid(it) } == true && backgroundState.displayId == ticket?.displayId
    val snap = if (validView) snapshot else null
    LaunchedEffect(snapshot, validView) {
        val captured = snapshot ?: return@LaunchedEffect
        if (!validView) { snapshot = null; selectedId = null; status = "副屏已变化，请重新打开布局"; return@LaunchedEffect }
        if (!captured.data.optBoolean("available")) return@LaunchedEffect
        while (true) {
            delay(1_000)
            val pkg = captured.data.optString("targetPackage")
            val valid = withContext(Dispatchers.IO) {
                runCatching {
                    val d = BackgroundOperation.currentDisplay()
                    ticket?.let { BackgroundOperation.isInspectorViewValid(it) } == true && d != null &&
                        d.displayId == captured.data.optInt("display_id") && d.generation == captured.data.optLong("generation") &&
                        d.width == captured.data.optInt("width") && d.height == captured.data.optInt("height") &&
                        d.inputWidth == captured.data.optInt("input_width") && d.inputHeight == captured.data.optInt("input_height") &&
                        d.rotation * 90 == captured.data.optInt("rotation") && d.bufferTurn == captured.data.optInt("frame_turn") &&
                        VScreenShell.topPackage(d.displayId) == pkg &&
                        VScreenShell.topActivity(d.displayId) == captured.data.optString("activity").takeIf { it.isNotBlank() && it != "null" } &&
                        VScreenStore.ruleFor(pkg) != VScreenStore.Rule.BLOCK
                }.getOrDefault(false)
            }
            if (!valid) { snapshot = null; selectedId = null; status = "目标页面已切换或关闭，请刷新"; break }
            if (BackgroundOperation.currentDisplay()?.frameVersion?.value != captured.data.optLong("frame_version")) {
                status = "页面已有更新：当前保留编号快照，点击刷新查看最新布局"
            }
        }
    }

    val boxes = remember(snap) { snap?.boxes().orEmpty() }
    val nodes = remember(snap) { snap?.data?.optJSONArray("nodes")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty() }
    val numbered = remember(nodes, boxes) {
        val byId = boxes.associateBy { it.id }
        nodes.mapIndexed { index, node -> InspectorNode(node, byId[node.optInt("id")], node.optInt("number", index + 1)) }
    }
    val rowIds = LayoutInspectorPolicy.visibleRows(boxes.map { box ->
        val n = numbered.firstOrNull { it.box?.id == box.id }
        box.copy(label = box.label + " " + n?.title.orEmpty())
    }, collapsed, query).map { it.id }.toSet()
    val filtered = numbered.filter { (!onlyClickable || it.node.optBoolean("clickable") || it.node.optBoolean("editable")) && it.box?.visible == true }
    val treeRows = filtered.filter { it.box?.id in rowIds }
    val selected = numbered.firstOrNull { selectedId != null && it.box?.id == selectedId }
    val currentReference = snap?.data?.optString("human_ref").orEmpty()
    val proposalMatches = proposal != null && proposal.status == "PENDING" && proposal.reference.equals(currentReference, true) && selected?.number == proposal.number

    Dialog(onDismissRequest = ::closeOnce, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0B1018)).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("布局选框", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { refresh++ }, enabled = ticket != null && !capturing && !reviewBusy) { Text(if (capturing) "采集中" else "刷新") }
                TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("复位") }
                TextButton(onClick = ::closeOnce) { Text("关闭") }
            }
            Text(status, color = Color(0xFF9CCBFF), fontSize = 12.sp)
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val canvasWidth = constraints.maxWidth.toFloat()
                val canvasHeight = constraints.maxHeight.toFloat()
                val availableWidth = maxWidth
                val availableHeight = maxHeight
                snap?.takeIf { it.data.optBoolean("available") }?.let { s ->
                    val data = s.data
                    Box(Modifier.fillMaxSize()) {
                        InspectorCanvas(s, filtered, selected?.box?.id, proposalMatches, zoom, pan,
                            { gestureZoom, gesturePan -> zoom = (zoom * gestureZoom).coerceIn(1f, 4f); pan += gesturePan },
                            { id -> selectedId = id }, Modifier.fillMaxSize())

                        // Narrow edge handle and every tool card stay in this Box; canvas size never changes.
                        Box(Modifier.align(Alignment.CenterEnd).width(22.dp).background(Color(0xCC1C2A3A)).clickable { toolOpen = !toolOpen }.padding(vertical = 20.dp)) {
                            Text(if (proposal?.status == "PENDING") "●" else if (toolOpen) "›" else "‹", color = if (proposal?.status == "PENDING") Color.Yellow else Color.White, modifier = Modifier.align(Alignment.Center))
                        }
                        if (toolOpen) {
                            val cardWidth = minOf(292.dp, (availableWidth - 24.dp).coerceAtLeast(1.dp))
                            val cardHeight = (availableHeight * 0.82f).coerceAtLeast(1.dp)
                            val maxToolX = (canvasWidth - with(density) { (cardWidth + 24.dp).toPx() }).coerceAtLeast(0f)
                            val maxToolY = (canvasHeight - toolSize.height).coerceAtLeast(0f)
                            val position = Offset(toolOffset.x.coerceIn(0f,maxToolX), toolOffset.y.coerceIn(0f,maxToolY))
                            Column(Modifier.offset { IntOffset(position.x.roundToInt(),position.y.roundToInt()) }
                                .width(cardWidth).heightIn(max = cardHeight).onSizeChanged { toolSize = it }
                                .background(Color(0xEE142131)).padding(8.dp)) {
                                Row(Modifier.fillMaxWidth().pointerInput(maxToolX, maxToolY) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        toolOffset = Offset((toolOffset.x + drag.x).coerceIn(0f,maxToolX), (toolOffset.y + drag.y).coerceIn(0f,maxToolY))
                                    }
                                }.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("拖动工具卡", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    Text("${data.optInt("display_id")}号副屏", color = Color(0xFF9DB2C6), fontSize = 11.sp)
                                }
                                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                                Text("${data.optInt("width")} ×${data.optInt("height")}px · ${data.optInt("nodeCount", nodes.size)}节点", color = Color(0xFFB6C2D1), fontSize = 11.sp)
                                Text(if (data.optBoolean("wireframe_enabled")) "点框只选中，不会向目标应用输入" else "当前仅线框/布局树，旧图不会强叠框", color = Color(0xFFF4C76B), fontSize = 11.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OutlinedButton(onClick = { treeOpen = !treeOpen }) { Text(if (treeOpen) "收起树" else "布局树") }
                                    TextButton(onClick = { onlyClickable = !onlyClickable }) { Text(if (onlyClickable) "可点框" else "全部框") }
                                }
                                if (proposal != null) {
                                    if (proposal.status == "PENDING") {
                                        TextButton(enabled = !reviewBusy && !capturing, onClick = {
                                            val t = ticket ?: return@TextButton
                                            reviewBusy = true
                                            val serial = ++requestSerial
                                            scope.launch {
                                                try {
                                                    val opened = withContext(Dispatchers.IO) { LayoutInspector.openProposalForLocal(proposal.reference, t) }
                                                    if (serial != requestSerial || ticket !== t || InspectorProposals.state.value?.id != proposal.id) return@launch
                                                    if (opened == null) status = "AI 建议快照已过期或不匹配"
                                                    else {
                                                        snapshot = opened
                                                        selectedId = opened.boxes().firstOrNull { it.id + 1 == proposal.number }?.id
                                                        onlyClickable = false
                                                        zoom = 1f; pan = Offset.Zero
                                                        status = "历史线框 · ${proposal.reference} / ${proposal.number}号"
                                                    }
                                                } finally { reviewBusy = false }
                                            }
                                        }) { Text("查看 AI 建议（${proposal.number}号）") }
                                    } else Text(when (proposal.status) {
                                        "APPROVED" -> "已确认，仅生成确认回执"
                                        "REJECTED" -> "已驳回"
                                        "STALE" -> "页面有变化，请让 AI 重新标记"
                                        "CANCELLED" -> "AI 已撤回建议"
                                        else -> "建议已过期"
                                    }, color = Color(0xFFF4C76B), fontSize = 11.sp)
                                }
                                if (treeOpen) {
                                    BasicTextField(query, { query = it }, Modifier.fillMaxWidth().background(Color(0xFF1B2A3A)).padding(6.dp), textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp))
                                    LazyColumn(Modifier.fillMaxWidth().height(148.dp)) {
                                        items(treeRows, key = { it.box!!.id }) { item ->
                                            Row(Modifier.fillMaxWidth().clickable { selectedId = item.box?.id }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text("#${item.number}  ${item.title}", color = if (item.box?.id == selectedId) Color.Yellow else Color.White, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                                selected?.let { item ->
                                    Text("编号 ${item.number} · ${item.title}", color = if (proposalMatches) Color(0xFFFFD45A) else Color.White, fontSize = 13.sp)
                                    Text("${item.box!!.left},${item.box!!.top} · ${item.box!!.right - item.box!!.left}×${item.box!!.bottom - item.box!!.top}px", color = Color(0xFFB9C6D5), fontSize = 10.sp)
                                    if (proposalMatches && proposal != null) {
                                        Text("AI 建议：${proposal.description}", color = Color.White, fontSize = 13.sp)
                                        Text("仅确认此控件和建议，不会自动操作应用。", color = Color(0xFFB9C6D5), fontSize = 11.sp)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        OutlinedButton(onClick = { copyReference(context, data, item) }) { Text("复制定位") }
                                        if (proposalMatches && proposal != null) {
                                            Button(enabled = !reviewBusy, onClick = {
                                                val t = ticket ?: return@Button
                                                reviewBusy = true
                                                scope.launch {
                                                    try {
                                                        val accepted = withContext(Dispatchers.IO) { InspectorProposals.decideLocal(proposal.id,t,true) }
                                                        status = if (accepted) "已本地确认（未执行修改）" else "未确认：页面、会话或建议已变化"
                                                    } finally { reviewBusy = false }
                                                }
                                            }) { Text("确认") }
                                            TextButton(enabled = !reviewBusy, onClick = {
                                                val t = ticket ?: return@TextButton
                                                reviewBusy = true
                                                scope.launch {
                                                    try {
                                                        val rejected = withContext(Dispatchers.IO) { InspectorProposals.decideLocal(proposal.id,t,false) }
                                                        status = if (rejected) "已驳回" else "建议已失效"
                                                    } finally { reviewBusy = false }
                                                }
                                            }) { Text("驳回") }
                                        }
                                    }
                                    TextButton(onClick = { technicalOpen = !technicalOpen }) { Text(if (technicalOpen) "收起详情" else "详情") }
                                    if (technicalOpen) Text(item.node.toString(2), color = Color(0xFFB9EBC9), fontSize = 9.sp, modifier = Modifier.heightIn(max = 100.dp).verticalScroll(rememberScrollState()))
                                }
                                Text("缩放 ${"%.1f".format(zoom)}× · 双指/拖动画布", color = Color.Gray, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                } ?: Spacer(Modifier.fillMaxSize())
            }
        }
    }
}

private data class InspectorNode(val node: JSONObject, val box: LayoutInspectorPolicy.Box?, val number: Int) {
    val title: String = LayoutInspectorPresentation.title(
        if (node.isNull("text")) null else node.optString("text").takeIf { it.isNotBlank() },
        if (node.isNull("contentDescription")) null else node.optString("contentDescription").takeIf { it.isNotBlank() },
        if (node.isNull("resourceId")) null else node.optString("resourceId").takeIf { it.isNotBlank() },
        if (node.isNull("class")) null else node.optString("class").takeIf { it.isNotBlank() },
        node.optBoolean("password"),
    )
}

@Composable
private fun InspectorCanvas(snapshot: LayoutSnapshot, nodes: List<InspectorNode>, selectedId: Int?, proposalSelected: Boolean, zoom: Float, pan: Offset, onTransform: (Float, Offset) -> Unit, onSelect: (Int) -> Unit, modifier: Modifier) {
    val sourceWidth = snapshot.data.optInt("input_width").coerceAtLeast(1)
    val sourceHeight = snapshot.data.optInt("input_height").coerceAtLeast(1)
    val wireframe = snapshot.data.optBoolean("wireframe_enabled")
    val bitmap = snapshot.bitmap?.takeIf { snapshot.data.optBoolean("boxes_enabled") && !snapshot.data.optBoolean("screenshot_suppressed") }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    Canvas(modifier.pointerInput(snapshot) {
        detectTransformGestures { _, gesturePan, gestureZoom, _ -> onTransform(gestureZoom, gesturePan) }
    }.pointerInput(snapshot, zoom, pan, nodes, wireframe) {
        detectTapGestures { point ->
            if (wireframe) {
                val transform = LayoutInspectorViewport.fit(size.width.toFloat(), size.height.toFloat(), sourceWidth, sourceHeight, zoom, pan.x, pan.y)
                val source = transform?.source(point.x, point.y)
                if (source != null) LayoutInspectorPolicy.hit(nodes.mapNotNull { it.box }, source.first, source.second)?.let(onSelect)
            }
        }
    }) {
        val transform = LayoutInspectorViewport.fit(size.width, size.height, sourceWidth, sourceHeight, zoom, pan.x, pan.y) ?: return@Canvas
        val dst = RectF(transform.left, transform.top, transform.left + sourceWidth * transform.scale, transform.top + sourceHeight * transform.scale)
        val native = drawContext.canvas.nativeCanvas
        native.drawColor(Color(0xFF080C12).toArgb())
        native.save()
        native.clipRect(34f, 26f, size.width - 8f, size.height - 8f)
        if (bitmap != null && !bitmap.isRecycled) native.drawBitmap(bitmap, null, dst, paint)
        else {
            paint.color = Color(0xFF213344).toArgb(); paint.strokeWidth = 1f; paint.style = Paint.Style.STROKE
            for (x in 0..sourceWidth step 100) native.drawLine(dst.left + x * transform.scale, dst.top, dst.left + x * transform.scale, dst.bottom, paint)
            for (y in 0..sourceHeight step 100) native.drawLine(dst.left, dst.top + y * transform.scale, dst.right, dst.top + y * transform.scale, paint)
        }
        if (wireframe) nodes.filter { it.box?.let { b -> b.visible && b.area > 0 } == true }.forEach { item ->
            val box = item.box!!; val selected = box.id == selectedId
            val rect = RectF(dst.left + box.left * transform.scale, dst.top + box.top * transform.scale, dst.left + box.right * transform.scale, dst.top + box.bottom * transform.scale)
            paint.color = if (selected) Color(0xFFFFD447).toArgb() else Color(0xFF64DB8B).toArgb()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = if (selected) 4f else 1f
            if (selected && proposalSelected) paint.strokeWidth = 6f
            native.drawRect(rect, paint)
            paint.style = Paint.Style.FILL; paint.textSize = 11.sp.toPx(); paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            if (selected || item.number <= 80) {
                val label = item.number.toString(); val tw = paint.measureText(label)
                paint.color = Color(0xDD08130D).toArgb(); native.drawRect(rect.left, rect.top, rect.left + tw + 6, rect.top + paint.textSize + 4, paint)
                paint.color = if (selected) Color(0xFFFFE890).toArgb() else Color(0xFFB8FFD1).toArgb(); native.drawText(label, rect.left + 3, rect.top + paint.textSize, paint)
            }
        }
        native.restore()
        paint.style = Paint.Style.FILL; paint.color = Color(0xFFB9CFE0).toArgb(); paint.textSize = 9.sp.toPx(); paint.strokeWidth = 1f
        for (x in 0..sourceWidth step 100) {
            val px = dst.left + x * transform.scale
            if (px in 34f..(size.width - 8f)) { native.drawLine(px, 20f, px, 26f, paint); if (x % 200 == 0) native.drawText(x.toString(), px, 17f, paint) }
        }
        for (y in 0..sourceHeight step 100) {
            val py = dst.top + y * transform.scale
            if (py in 26f..(size.height - 8f)) { native.drawLine(28f, py, 34f, py, paint); if (y % 200 == 0) native.drawText(y.toString(), 0f, py, paint) }
        }
    }
}

private fun copyReference(context: Context, data: JSONObject, item: InspectorNode) {
    val resourceId = item.node.optString("resourceId")
    val safeText = if (item.node.optBoolean("password")) "" else item.title
    val value = "${data.optString("human_ref", data.optString("snapshot_id", "无快照"))} / ${item.number}号 · ${item.title} · $resourceId${if (safeText.isBlank()) "" else " · $safeText"}"
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("布局定位", value))
}

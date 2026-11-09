package com.kilon.agentvscreen.ui

import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.draw.rotate
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.R
import com.kilon.agentvscreen.api.AgentGuide
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.host.A11yHeal
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.host.HostService
import com.kilon.agentvscreen.host.Prefs
import com.kilon.agentvscreen.host.Setup
import com.kilon.agentvscreen.host.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home: is it running, is it set up, how an agent connects, the few settings, and diagnostics. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AvsTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Home() } } }
    }

    override fun onResume() {
        super.onResume()
        // In front now, so a foreground service may start: repair a start the system refused in the background,
        // and retry a port that was taken.
        if (Prefs.serviceOn(this)) HostService.start(this)
        Overlay.refresh()
    }

    private fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "已复制：$label", Toast.LENGTH_SHORT).show()
    }

    private fun diagnostics(items: List<Setup.Item>): String {
        val s = BackgroundOperation.state.value
        return buildString {
            appendLine("Agent-VScreen ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("服务：${if (HostService.isAlive) "运行中" else "已停止"} · 127.0.0.1:${ApiConfig.port} · ${HostService.server?.lastError ?: "无错误"}")
            appendLine("后台：${com.kilon.agentvscreen.host.Liveness.json()}")
            appendLine("连接：${s.connection}${s.connectionError?.let { "（$it）" } ?: ""} · 副屏 ${s.displayId ?: "无"} ${s.screenWidth}x${s.screenHeight} · 会话 ${s.ownerSessionId ?: "-"} · ${Palette.word(modeOf(s))}")
            items.forEach { appendLine("${if (it.ok) "✓" else "✗"} ${it.label}：${it.detail}") }
            appendLine("无障碍自动恢复：${A11yHeal.lastResult ?: "-"}")
            appendLine()
            appendLine("—— 最近日志 ——")
            append(AppLogger.tail(300))
        }
    }

    @Composable
    private fun Home() {
        val scope = rememberCoroutineScope()
        val s by BackgroundOperation.state.collectAsState()
        val sh by ShizukuManager.snapshot.collectAsState()
        var tick by remember { mutableIntStateOf(0) }
        var items by remember { mutableStateOf(emptyList<Setup.Item>()) }
        var setupLog by remember { mutableStateOf<List<String>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        var serviceOn by remember { mutableStateOf(Prefs.serviceOn(this)) }
        var setupOpen by remember { mutableStateOf(false) }
        LaunchedEffect(tick) {
            while (true) {
                items = withContext(Dispatchers.IO) { runCatching { BackgroundOperation.refreshStatus(); Setup.check(this@MainActivity) }.getOrDefault(items) }
                delay(2_000)
            }
        }
        val allOk = items.isNotEmpty() && items.filter { it.key != "accessibility" }.all { it.ok }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Hero(s, serviceOn) { on ->
                serviceOn = on; Prefs.setServiceOn(this@MainActivity, on)
                if (on) HostService.start(this@MainActivity) else { BackgroundOperation.stopAndRelease("用户停止了服务"); HostService.stop(this@MainActivity) }
                tick++
            }

            if (s.userStopped) Banner(
                "你停止了 AI", "你在悬浮窗上按了“停止 AI”${if (s.stoppedAt > 0) "（" + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(s.stoppedAt)) + "）" else ""}。在你允许之前，agent 的每个操作都会被拒绝，它也会被告知来找你。关掉悬浮窗不会解除它。", Palette.stopped,
                "允许继续",
            ) { BackgroundOperation.userResume() }
            if (s.windowHidden && s.displayId != null && !s.userStopped) Banner(
                "小窗已收起", "AI 还在后台工作。下一轮开始时小窗会自己回来，也可以现在打开。", Palette.standby, "显示小窗",
            ) { BackgroundOperation.showWindow() }

            com.kilon.agentvscreen.host.Liveness.problem()?.let { p ->
                Banner("后台运行受限", p, Palette.stopped, "去设置") {
                    val opened = com.kilon.agentvscreen.host.Liveness.openBackgroundSettings(this@MainActivity)
                    Toast.makeText(this@MainActivity, "已打开：$opened。把 Agent-VScreen 设为允许自启动、关联启动和后台活动", Toast.LENGTH_LONG).show()
                }
            }

            // ── setup ──
            val done = items.count { it.ok }
            Block(
                title = "首次配置",
                trailing = { Text("$done/${items.size}", color = if (allOk) Palette.takeover else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) },
                onHeader = if (allOk) ({ setupOpen = !setupOpen }) else null,
                open = if (allOk) setupOpen else null,
            ) {
                if (allOk && !setupOpen) {
                    Text("全部就绪。只用 Shizuku：不占用 adb、不改无线调试，你的 agent 自己的 adb / Shizuku 照常用。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    return@Block
                }
                Text("只用 Shizuku 这一条可共享的特权通道：不占用 adb，不改无线调试，不重启 adbd。每一项都只把本应用“加进去”，不动别的应用的设置。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                items.forEachIndexed { i, it -> StepRow(i + 1, it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    when (sh.state) {
                        ShizukuManager.State.NEED_PERMISSION -> Button(onClick = { ShizukuManager.requestPermission(); tick++ }) { Text("授权 Shizuku") }
                        ShizukuManager.State.NOT_RUNNING -> Button(onClick = { ShizukuManager.openShizukuApp(this@MainActivity) }) { Text("打开 Shizuku 启动") }
                        ShizukuManager.State.NOT_INSTALLED -> Button(onClick = { ShizukuManager.openInstallPage(this@MainActivity) }) { Text("安装 Shizuku") }
                        ShizukuManager.State.READY -> Button(enabled = !busy && items.any { !it.ok }, onClick = {
                            busy = true
                            scope.launch { setupLog = withContext(Dispatchers.IO) { Setup.grantAll(this@MainActivity) }; busy = false; Overlay.refresh(); tick++ }
                        }) { Text(if (busy) "配置中…" else "一键配置其余项") }
                    }
                    if (items.any { it.key == "vendor_bg" && !it.ok }) Button(onClick = {
                        val opened = com.kilon.agentvscreen.host.Liveness.openBackgroundSettings(this@MainActivity)
                        Toast.makeText(this@MainActivity, "已打开：$opened。把 Agent-VScreen 改成手动管理，允许自启动、关联启动和后台活动", Toast.LENGTH_LONG).show()
                        tick++
                    }) { Text("去设置后台管理") }
                    OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("无障碍设置") }
                    if (!Settings.canDrawOverlays(this@MainActivity)) OutlinedButton(onClick = {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    }) { Text("悬浮窗权限") }
                }
                setupLog.forEach { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            Connect()

            // ── settings ──
            Block("设置") {
                Toggle("悬浮窗", "有副屏时在所有应用上方显示", s.showPreview) { BackgroundOperation.setShowPreview(it); if (it) Overlay.refresh() }
                Toggle("精确文字识别", "PP-OCR，在本机运行", s.accurateOcr) { BackgroundOperation.setAccurateOcr(it) }
                Toggle("后台录制", "【这段时间】里的拼图", s.recording) { BackgroundOperation.setRecording(it) }
                val idleSave by com.kilon.agentvscreen.core.VScreenStore.idlePowerSave.collectAsState()
                Toggle("空闲省电", "一轮结束 30 秒后暂停副屏渲染，页面保留，下一条命令自动恢复；个别应用恢复时会重新加载，遇到可以关掉", idleSave) {
                    BackgroundOperation.setIdlePowerSave(it)
                }
                Text("一轮的长度", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text("agent 安静多久算这轮结束：之后副屏省电暂停，10 分钟后释放（agent 也可以用 vscreen done 马上结束）",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var quiet by remember { mutableIntStateOf(ApiConfig.quietMinutes) }
                val opts = listOf(1, 3, 5, 10, 30)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    opts.forEachIndexed { i, m ->
                        SegmentedButton(selected = quiet == m, onClick = { ApiConfig.quietMinutes = m; quiet = m },
                            shape = SegmentedButtonDefaults.itemShape(i, opts.size), icon = {}) { Text("${m}分", maxLines = 1, fontSize = 13.sp) }
                    }
                }
            }
            Block("应用限制") { VScreenAppRules() }

            Diagnostics(items)
            Text("Agent-VScreen ${BuildConfig.VERSION_NAME} · 副屏能力来自 Minis VScreen", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp))
        }
    }

    @Composable
    private fun Hero(s: BackgroundOperation.State, serviceOn: Boolean, setOn: (Boolean) -> Unit) {
        val mode = modeOf(s)
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2B1F7A), Color(0xFF151A3D), Color(0xFF0B2A44))))
                .padding(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_logo), null, Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Agent-VScreen", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("给任何手机 agent 用的后台副屏", color = Color(0xB3FFFFFF), fontSize = 13.sp)
                    }
                    Switch(checked = serviceOn, onCheckedChange = setOn)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val ok = HostService.isAlive && HostService.server?.lastError == null
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Palette.takeover else Palette.stopped))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (HostService.isAlive) (HostService.server?.lastError ?: "服务运行中 · 127.0.0.1:${ApiConfig.port}") else "服务已停止",
                        color = Color.White, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (HostService.isAlive && HostService.server?.lastError != null)
                        if (HostService.server?.lastError?.contains("别的程序") == true)
                            TextButton(onClick = {
                                val np = if (ApiConfig.port >= 65535) ApiConfig.DEFAULT_PORT + 1 else ApiConfig.port + 1
                                ApiConfig.setPort(np); HostService.server?.start(np)
                                Toast.makeText(this@MainActivity, "端口改成 $np：请在“接入 agent”里重新复制接入命令给 agent", Toast.LENGTH_LONG).show()
                            }) { Text("换端口", color = Color.White) }
                        else TextButton(onClick = { HostService.server?.start(ApiConfig.port) }) { Text("立即重试", color = Color.White) }
                }
                // The screen right now.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x1FFFFFFF)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(width = 30.dp, height = 46.dp).glowBorder(if (s.displayId == null) Mode.IDLE else mode, 7.dp, width = 2.dp, pad = 4.dp)
                        .clip(RoundedCornerShape(7.dp)).background(Palette.ink), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.padding(bottom = 4.dp).width(12.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xB3FFFFFF)))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (s.displayId == null) "还没有副屏" else Palette.word(mode), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            if (s.displayId == null) "agent 第一次 launch 时自动创建"
                            else (s.agentTitle ?: "副屏 ${s.screenWidth}×${s.screenHeight} · 会话 ${s.ownerSessionId ?: "-"}"),
                            color = Color(0xB3FFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (s.displayId != null) TextButton(onClick = { startActivity(Intent(this@MainActivity, FullscreenActivity::class.java)) }) {
                        Text("查看", color = Color.White)
                    }
                }
            }
        }
    }

    @Composable
    private fun Connect() {
        var tab by remember { mutableIntStateOf(0) }
        var token by remember { mutableStateOf(ApiConfig.token) }
        var showToken by remember { mutableStateOf(false) }
        var rawOpen by remember { mutableStateOf(false) }
        val ledger by com.kilon.agentvscreen.api.AgentLedger.snap.collectAsState()
        Block("接入 agent") {
            val tabs = listOf("命令行", "MCP", "HTTP")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { i, t ->
                    SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, tabs.size), icon = {}) { Text(t, fontSize = 13.sp) }
                }
            }
            val hint = @Composable { t: String -> Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            when (tab) {
                0 -> {
                    hint("Termux、Minis 等带终端的 agent：复制下面这段话发给它。它会装好 vscreen 命令，并按你的要求把一段说明存进它自己的长期记忆，以后的新对话里也知道手机上有这个工具。")
                    Button(onClick = { copy("给 agent 的话", AgentGuide.onboard()) }, modifier = Modifier.fillMaxWidth()) { Text("复制给 agent 的话（推荐）") }
                    TextButton(onClick = { rawOpen = !rawOpen }) { Text(if (rawOpen) "收起安装命令" else "只要安装命令") }
                    AnimatedVisibility(rawOpen) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            hint("只把命令给 agent 的话，大多数 agent 装完就忘了，新对话里不知道有 vscreen。")
                            Code(ApiConfig.installLine()) { copy("安装命令", ApiConfig.installLine()) }
                        }
                    }
                }
                1 -> {
                    hint("支持 MCP 的 agent：把这段加进它的 MCP 配置（streamable HTTP，一个工具 vscreen，截图直接附在结果里）。每次连接时都会带上用法说明，新对话也知道怎么用。")
                    Code(ApiConfig.mcpJson()) { copy("MCP 配置", ApiConfig.mcpJson()) }
                }
                else -> {
                    hint("自己写调用代码的 agent 应用：复制下面这段话发给它，里面有地址、令牌和要它记住的说明。返回 JSON（text、data、images、exit、code），完整说明 GET /v1/help。")
                    Button(onClick = { copy("给 agent 的话", AgentGuide.onboardHttp()) }, modifier = Modifier.fillMaxWidth()) { Text("复制给 agent 的话") }
                }
            }
            AgentMemoryRow(ledger)
            OutlinedButton(onClick = { copy("记忆说明", if (tab == 2) AgentGuide.memoryHttp() else AgentGuide.memory()) }, modifier = Modifier.fillMaxWidth()) {
                Text("复制一句话，放进 agent 的自定义指令 / 记忆")
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("令牌", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(if (showToken) token else "•".repeat(12) + token.takeLast(4), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { showToken = !showToken }) { Icon(if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "显示令牌") }
                IconButton(onClick = { copy("令牌", token) }) { Icon(Icons.Filled.ContentCopy, "复制令牌") }
                IconButton(onClick = { ApiConfig.resetToken(); token = ApiConfig.token; Toast.makeText(this@MainActivity, "已换新令牌：旧的立即失效，各 agent 要重新接入", Toast.LENGTH_LONG).show() }) {
                    Icon(Icons.Filled.Refresh, "换一个令牌")
                }
            }
            Text("令牌只给你信任的 agent：拿到它就能在副屏上操作你的应用。服务只监听本机，网页调用一律拒绝。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Remote(token)
        }
    }

    /** 1.1.6: did the agent keep VScreen in mind? Facts only (see AgentLedger). */
    @Composable
    private fun AgentMemoryRow(l: com.kilon.agentvscreen.api.AgentLedger.Snapshot) {
        if (!l.installed) return
        val fmt = remember { java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA) }
        val (color, title, body) = when {
            l.cameBack -> Triple(Palette.takeover, "agent 记住了", "安装后，agent 在隔了一段时间的新对话里又主动用了 ${l.comebacks} 次（最近 ${fmt.format(java.util.Date(l.lastComebackAt))}）。" +
                (l.rememberedWhere?.let { "它说存在了「$it」。" } ?: ""))
            l.remembered -> Triple(Palette.standby, "agent 说已记住", "存在了「${l.rememberedWhere}」（${fmt.format(java.util.Date(l.rememberedAt))}）。等它在新对话里主动用起来，这里会变成“记住了”。")
            else -> Triple(Palette.stopped, "agent 还没确认记住", "${fmt.format(java.util.Date(l.installAt))} 装好了 vscreen，但 agent 没有报告把说明存在哪。新对话里它可能不知道有这个工具：再把“给 agent 的话”发给它一次，或把下面那句放进它的自定义指令。")
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.10f))
                .border(BorderStroke(1.dp, color.copy(alpha = 0.45f)), RoundedCornerShape(14.dp)).padding(12.dp),
        ) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
            Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    /** 1.1.6: other devices: off / built-in Tailscale (no app needed) / the Tailscale app on this phone. */
    @Composable
    private fun Remote(token: String) {
        var mode by remember { mutableStateOf(ApiConfig.tailnetMode) }
        val ts by com.kilon.agentvscreen.host.Tailnet.status.collectAsState()
        var sysAddr by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(mode) { while (true) { sysAddr = HostService.server?.tailAddress; delay(3_000) } }
        val M = com.kilon.agentvscreen.host.Tailnet.Mode.entries
        Text("让其它设备连进来", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Text("默认关。打开后只有你自己 Tailscale 网络里的电脑或手机能连，走加密隧道，局域网和公网都连不到；令牌照样要。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val labels = listOf("关", "内置 Tailscale", "Tailscale App")
            M.forEachIndexed { i, m ->
                SegmentedButton(selected = mode == m, onClick = {
                    mode = m; ApiConfig.tailnetMode = m
                    HostService.server?.start(ApiConfig.port)
                    com.kilon.agentvscreen.host.Tailnet.sync(applicationContext)
                }, shape = SegmentedButtonDefaults.itemShape(i, M.size), icon = {}) { Text(labels[i], fontSize = 12.sp, maxLines = 1) }
            }
        }
        val remoteLine: @Composable (String) -> Unit = { host ->
            val line = "VSCREEN_URL=http://$host:${ApiConfig.port} VSCREEN_TOKEN=$token vscreen status"
            Text("在你 Tailscale 网络里的另一台设备上（装好 curl，先 curl -fsS http://$host:${ApiConfig.port}/cli > vscreen && chmod +x vscreen）：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Code(line) { copy("远程调用示例", line) }
        }
        when (mode) {
            com.kilon.agentvscreen.host.Tailnet.Mode.BUILTIN -> {
                var key by remember { mutableStateOf("") }
                var ctl by remember { mutableStateOf(ApiConfig.tailnetControlUrl ?: "") }
                var more by remember { mutableStateOf(false) }
                if (!com.kilon.agentvscreen.host.Tailnet.available(this@MainActivity)) {
                    Text("这个安装包里没有内置 Tailscale 组件（只支持 arm64 手机）。", fontSize = 12.sp, color = Palette.stopped)
                } else when {
                    ts.loggedIn -> {
                        Text("已连上 Tailscale：本机在你的网络里叫 ${ts.dnsName ?: ApiConfig.tailnetHostname}，地址 ${ts.ip}。" +
                            (ts.user?.let { "账号 $it，" } ?: "") + "在线设备 ${ts.online} 台。" + (ts.lastPeer?.let { "最近来访：$it。" } ?: ""),
                            fontSize = 12.sp, color = Palette.takeover)
                        remoteLine(ts.ip!!)
                        TextButton(onClick = { com.kilon.agentvscreen.host.Tailnet.logout() }) { Text("退出 Tailscale 登录") }
                    }
                    ts.needsLogin && ts.authUrl != null -> {
                        Text("第一次使用要登录一次 Tailscale（之后会记住）。用你电脑上 Tailscale 的同一个账号登录。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ts.authUrl))) } }, modifier = Modifier.fillMaxWidth()) { Text("在浏览器里登录 Tailscale") }
                        TextButton(onClick = { copy("登录链接", ts.authUrl!!) }) { Text("复制登录链接（在电脑上打开也行）") }
                    }
                    ts.error != null -> Text("内置 Tailscale：${ts.error}（会自动重试）", fontSize = 12.sp, color = Palette.stopped)
                    else -> Text("内置 Tailscale 正在启动（${ts.state}）…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { more = !more }) { Text(if (more) "收起高级选项" else "高级：auth key、自建控制服务器") }
                AnimatedVisibility(more) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        androidx.compose.material3.OutlinedTextField(key, { key = it }, label = { Text("auth key（可选，代替浏览器登录）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        androidx.compose.material3.OutlinedTextField(ctl, { ctl = it }, label = { Text("控制服务器（可选，如 Headscale 地址）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = {
                            if (key.isNotBlank()) ApiConfig.tailnetAuthKey = key
                            ApiConfig.tailnetControlUrl = ctl.ifBlank { null }
                            key = ""
                            com.kilon.agentvscreen.host.Tailnet.restart(applicationContext)
                            Toast.makeText(this@MainActivity, "已保存，内置 Tailscale 重新连接中", Toast.LENGTH_SHORT).show()
                        }) { Text("保存并重连") }
                    }
                }
            }
            com.kilon.agentvscreen.host.Tailnet.Mode.SYSTEM -> {
                val a = sysAddr
                if (a == null) Text("还没找到 Tailscale 地址：在这台手机上装好并登录 Tailscale App，打开连接后 20 秒内自动生效。不想装 App 就选“内置 Tailscale”。", fontSize = 12.sp, color = Palette.stopped)
                else remoteLine(a)
            }
            else -> {}
        }
    }

    @Composable
    private fun Diagnostics(items: List<Setup.Item>) {
        var open by remember { mutableStateOf(false) }
        Block("诊断", trailing = { Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null) }, onHeader = { open = !open }) {
            Text("出问题时把诊断信息发给开发者：版本、配置状态和最近日志（不含令牌、不含你输入的字）。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy("诊断信息", diagnostics(items)) }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("复制") }
                OutlinedButton(onClick = {
                    runCatching {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Agent-VScreen 诊断").putExtra(Intent.EXTRA_TEXT, diagnostics(items)), "发送诊断"))
                    }
                }) { Icon(Icons.Filled.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("分享") }
            }
            AnimatedVisibility(open) {
                SelectionContainer {
                    Text(AppLogger.tail(80), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp))
                }
            }
        }
    }

    @Composable
    private fun Banner(title: String, body: String, color: Color, action: String, onAction: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.12f))
                .border(BorderStroke(1.dp, color.copy(alpha = 0.5f)), RoundedCornerShape(20.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = color)
                Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = color)) { Text(action) }
        }
    }

    @Composable
    private fun StepRow(n: Int, it: Setup.Item) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (it.ok) Icon(Icons.Filled.CheckCircle, null, tint = Palette.takeover, modifier = Modifier.size(24.dp))
            else Box(Modifier.size(24.dp).clip(CircleShape).border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Text("$n", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(it.label + if (it.key == "accessibility") "（可选）" else "", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(it.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun Block(title: String, trailing: (@Composable () -> Unit)? = null, onHeader: (() -> Unit)? = null, open: Boolean? = null, content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp)).animateContentSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth().then(if (onHeader != null) Modifier.clickable(onClick = onHeader) else Modifier), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                trailing?.invoke()
                // A block that folds says so: a "展开 / 收起" chip with a turning chevron, not just a tappable title.
                if (open != null) {
                    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "chevron")
                    Spacer(Modifier.width(10.dp))
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (open) "收起" else "展开", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Icon(Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp).rotate(turn))
                    }
                }
            }
            content()
        }
    }

    @Composable
    private fun Code(text: String, onCopy: () -> Unit) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF12141C))) {
            SelectionContainer {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.sp, color = Color(0xFFE6E8F0),
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 48.dp))
            }
            IconButton(onClick = onCopy, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Filled.ContentCopy, "复制", tint = Color(0xFFE6E8F0)) }
        }
    }

    @Composable
    private fun Toggle(label: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { set(!on) }) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = on, onCheckedChange = set)
        }
    }
}

package com.kilon.agentvscreen.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kilon.agentvscreen.core.BackgroundOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Human-only controls for creating and managing ATVSX's own manual display. */
@Composable
internal fun ManualDisplayControls(
    activity: ComponentActivity,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by BackgroundOperation.state.collectAsState()
    val manualOwn = BackgroundOperation.manuallyOpened()
    val hasDisplay = state.displayId != null
    val aiOwnedDisplay = hasDisplay && !manualOwn
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var chooserOpen by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }

    fun showError(t: Throwable) {
        if (t is CancellationException) throw t
        error = t.message?.takeIf { it.isNotBlank() } ?: "操作失败，请稍后重试"
    }

    fun <T> runIo(action: () -> T, success: ((T) -> Unit)? = null) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { action() }
                busy = false
                success?.invoke(result)
            } catch (t: Throwable) {
                busy = false
                showError(t)
            }
        }
    }

    fun openManualDisplay() {
        runIo(
            action = { BackgroundOperation.openManualDisplay() },
            success = { id ->
                Toast.makeText(context, "副屏已打开（$id），可手动选应用", Toast.LENGTH_SHORT).show()
            },
        )
    }

    fun loadApps() {
        if (loadingApps || busy) return
        loadingApps = true
        error = null
        scope.launch {
            try {
                apps = withContext(Dispatchers.IO) { queryLaunchableApps(context) }
                chooserOpen = true
            } catch (t: Throwable) {
                showError(t)
            } finally {
                loadingApps = false
            }
        }
    }

    fun launch(app: LaunchableApp) {
        runIo(
            action = { BackgroundOperation.launchManualApp(app.packageName) },
            success = { message: String -> chooserOpen = false; Toast.makeText(context, message, Toast.LENGTH_LONG).show() },
        )
    }

    fun closeManualDisplay() {
        runIo(
            action = { BackgroundOperation.closeManualDisplay() },
            success = { _: Unit -> Toast.makeText(context, "手动副屏已关闭", Toast.LENGTH_SHORT).show() },
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
    ) {
        when {
            aiOwnedDisplay -> {
                Text(
                    "已有副屏会保留原页面和会话。这里可以唤起查看，不抢占、切换应用或关闭。",
                    fontSize = if (compact) MaterialTheme.typography.bodySmall.fontSize else MaterialTheme.typography.bodyMedium.fontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { runIo(action = { BackgroundOperation.openManualDisplay() }, success = { _: Int -> activity.startActivity(Intent(activity, FullscreenActivity::class.java)) }) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("全屏查看当前副屏") }
            }
            else -> {
                if (!compact) {
                    Text(
                        if (manualOwn) "这是你手动打开并持有的副屏。可选应用，或只在确认后关闭副屏生命周期。"
                        else "没有副屏时，你可以手动打开一个独立副屏；不会自动启动应用，也不会替你处理权限或登录。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!hasDisplay) {
                    Button(
                        onClick = ::openManualDisplay,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { ButtonLabel(if (busy) "正在打开副屏…" else "⏻ 一键打开副屏", busy) }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = ::loadApps, enabled = !busy && !loadingApps, modifier = Modifier.weight(1f)) {
                            ButtonLabel(if (loadingApps) "加载应用…" else "选应用", loadingApps)
                        }
                        OutlinedButton(
                            onClick = { runIo(action = { BackgroundOperation.openManualDisplay() }, success = { _: Int -> activity.startActivity(Intent(activity, FullscreenActivity::class.java)) }) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("全屏查看") }
                    }
                    if (manualOwn) {
                        TextButton(onClick = { runIo(action = { BackgroundOperation.yieldManualDisplay() }, success = { _: Unit -> Toast.makeText(context, "页面已保留，等待 AI 明确接手；停止状态不变", Toast.LENGTH_SHORT).show() }) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("保留页面并交给 AI")
                        }
                        OutlinedButton(
                            onClick = { confirmClose = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("关闭手动副屏") }
                    }
                }
            }
        }
        error?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error) }
    }

    if (chooserOpen) {
        val filtered = apps.filter {
            search.isBlank() || it.label.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = { chooserOpen = false },
            title = { Text("选择要在副屏打开的应用") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        singleLine = true,
                        label = { Text("搜索应用名或包名") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (filtered.isEmpty()) {
                        Text("没有匹配的可启动应用", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                            items(filtered, key = { it.packageName }) { app ->
                                TextButton(onClick = { launch(app) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(app.label, maxLines = 1)
                                        Text(app.packageName, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooserOpen = false }) { Text("取消") } },
        )
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("关闭手动副屏？") },
            text = { Text("只关闭副屏生命周期，不卸载应用，也不强制停止应用。当前手动页面将不再保留。") },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = { confirmClose = false; closeManualDisplay() }) { Text("确认关闭") }
            },
        )
    }
}

private data class LaunchableApp(val label: String, val packageName: String)

private fun queryLaunchableApps(context: Context): List<LaunchableApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return context.packageManager.queryIntentActivities(intent, 0)
        .asSequence()
        .mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            val pkg = activity.packageName
            if (pkg == context.packageName || pkg == "android" || pkg == "com.android.systemui" ||
                pkg.contains("resolver", true) || activity.name.contains("resolver", true)) return@mapNotNull null
            val label = info.loadLabel(context.packageManager)?.toString()?.trim().orEmpty().ifBlank { pkg }
            LaunchableApp(label, pkg)
        }
        .distinctBy { it.packageName }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }.thenBy { it.packageName })
        .toList()
}

@Composable
private fun ButtonLabel(text: String, busy: Boolean) {
    if (busy) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Text(text)
        }
    } else Text(text)
}

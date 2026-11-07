package com.kilon.agentvscreen.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-app rules (禁止操作 / 只读), drawn inside the home page's common card (MainActivity.Block) like every other
 * section. Only the user changes these; the agent sees them in `status` and is refused by them.
 * (The idle power-saving switch lives with the other switches in 设置.)
 */
@Composable
fun VScreenAppRules() {
    val rules by VScreenStore.rules.collectAsState()
    val ctx = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            runCatching {
                val pm = ctx.packageManager
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                    .distinctBy { it.second }
                    .filter { it.second != ctx.packageName }
                    .sortedBy { it.first }
            }.getOrDefault(emptyList())
        }
        labels = apps.associate { it.second to it.first }
    }

    Text(
        "禁止操作：AI 不能在后台打开、读屏或操作这个应用。只读：AI 可以打开、看和滚动，但不能点击、输入或滑动。只在后台副屏里生效，不影响你自己用。",
        fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (rules.isEmpty()) {
        Text("还没有限制：后台可以打开和操作所有应用（付费、删除等按钮仍需你确认）。", fontSize = 13.sp)
    } else {
        rules.entries.sortedBy { labels[it.key] ?: it.key }.forEach { (pkg, rule) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(labels[pkg] ?: pkg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${rule.zh} · $pkg", fontSize = 12.sp,
                        color = if (rule == VScreenStore.Rule.BLOCK) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = {
                    VScreenStore.setRule(pkg, if (rule == VScreenStore.Rule.BLOCK) VScreenStore.Rule.READONLY else VScreenStore.Rule.BLOCK)
                }) { Text(if (rule == VScreenStore.Rule.BLOCK) "改只读" else "改禁止") }
                TextButton(onClick = { VScreenStore.setRule(pkg, null) }) { Text("移除") }
            }
        }
    }
    OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("添加应用") }

    if (adding) AddRuleDialog(apps, rules.keys, onDismiss = { adding = false }) { pkg, rule ->
        VScreenStore.setRule(pkg, rule)
        adding = false
    }
}


@Composable
private fun AddRuleDialog(
    apps: List<Pair<String, String>>,
    existing: Set<String>,
    onDismiss: () -> Unit,
    onPick: (String, VScreenStore.Rule) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Pair<String, String>?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(picked?.first ?: "选择应用") },
        text = {
            val p = picked
            if (p == null) {
                Column {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("搜索名称或包名") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    val q = query.trim().lowercase()
                    val shown = apps.filter { it.second !in existing && (q.isEmpty() || it.first.lowercase().contains(q) || it.second.lowercase().contains(q)) }
                    LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                        items(shown, key = { it.second }) { a ->
                            Column(Modifier.fillMaxWidth().clickable { picked = a }.padding(vertical = 8.dp)) {
                                Text(a.first, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(a.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { onPick(p.second, VScreenStore.Rule.BLOCK) }, modifier = Modifier.fillMaxWidth()) {
                        Text("禁止操作（不能打开、读屏、操作）")
                    }
                    OutlinedButton(onClick = { onPick(p.second, VScreenStore.Rule.READONLY) }, modifier = Modifier.fillMaxWidth()) {
                        Text("只读（能看和滚动，不能点击输入）")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { if (picked != null) picked = null else onDismiss() }) { Text(if (picked != null) "返回" else "取消") }
        },
    )
}

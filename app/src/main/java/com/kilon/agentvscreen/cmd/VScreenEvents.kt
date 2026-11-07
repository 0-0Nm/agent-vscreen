package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.api.EventHub
import com.kilon.agentvscreen.core.VScreenRules
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** `events [--wait T]`: take the events posted for this session (a keep with --keep wake saw its condition). */
internal fun VScreenOffloadHandler.eventsCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: VScreenOffloadHandler.DEFAULT_SESSION
    val want = args.getLong("wait") ?: 0L
    // 1.1: events come only from this session's running --keep wake watches. With none running, waiting cannot bring
    // anything, so the reply says that at once instead of silently returning early or uselessly blocking.
    val watching = VScreenRules.running().count { it.spec.wake && it.spec.sid == s }
    val t0 = android.os.SystemClock.elapsedRealtime()
    val evs = EventHub.take(s, if (watching > 0) want else 0L)
    val waited = android.os.SystemClock.elapsedRealtime() - t0
    if (evs.isEmpty()) return text(when {
        watching == 0 && want > 0 -> "没有新事件，也没等：会话 $s 没有正在运行的 --keep wake 盯守，等也不会来事件。先 act --stop \"条件\" --keep wake 开始盯，keep 看列表。"
        watching == 0 -> "没有新事件（会话 $s 没有正在运行的 --keep wake 盯守）。"
        want > 0 -> "等了 ${waited / 1000}s，没有新事件（会话 $s 有 $watching 个盯守在运行）。"
        else -> "没有新事件（会话 $s 有 $watching 个盯守在运行；--wait 60s 可以等）。"
    })
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    return text(evs.joinToString("\n\n") { e -> "[${fmt.format(Date(e.optLong("at")))}] ${e.optString("kind")}「${e.optString("name")}」\n${e.optString("text")}" })
}

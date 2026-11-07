package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.VScreenKnowledge
import com.kilon.agentvscreen.core.VScreenMap
import com.kilon.agentvscreen.core.VScreenRules
import com.kilon.agentvscreen.core.VScreenShell

// [vscreen] v49: what the agent writes down per app (`note`) and the watches kept after a turn (`keep`).
// Notes are the agent's own words; the phone never derives meaning from them. The profile holds the facts the
// runner acts on: the pause / resume steps (turn-based realtime apps), realtime, orientation.

/** The app `note` works on: the one this chat launched, else the one on the background screen. */
private fun VScreenOffloadHandler.noteApp(sid: String?): String {
    VScreenKnowledge.journey(sid)?.let { return it }
    val d = sid?.let { BackgroundOperation.previewDisplayFor(it) }
    d?.let { runCatching { VScreenShell.topPackage(it.displayId) }.getOrNull() }?.takeIf { it != context.packageName }?.let { return it }
    VScreenKnowledge.lastApp(sid)?.let { return it }
    throw ActionError("NO_APP", "不知道记到哪个应用：先 launch 应用")
}

/** Everything written down about [pkg], as lines. */
internal fun noteLines(pkg: String): List<String> {
    val m = VScreenMap.load(pkg)
    val out = ArrayList<String>()
    val name = VScreenKnowledge.label(pkg)?.let { "$it（$pkg）" } ?: pkg
    out += "【$name】"
    if (m.notes.isEmpty()) out += "笔记：无" else { out += "笔记："; m.notes.forEach { out += "  ${it.id}. ${it.text}" } }
    if (m.segments.isEmpty()) out += "段：无" else { out += "段（act \"use 名字\"）："; m.segments.forEach { out += "  ${it.name} = ${it.steps}（用过 ${it.uses} 次）" } }
    val p = m.profile
    out += "暂停：" + (p["pause"] ?: "没记") + "；继续：" + (p["resume"] ?: "没记") +
        (if (p["pause"] != null && p["resume"] != null) "（回合制：每段 act 先继续、末尾暂停）" else "") +
        "；实时：" + (if (p["realtime"] == "true") "是" else "否") + (p["orientation"]?.let { "；方向：$it" } ?: "")
    return out
}

/**
 * `note` — list; `note <文字>` — add; `note pause "<步骤>"` / `note resume "<步骤>"` — the steps that pause / resume this
 * app (both written → every act segment runs resume … pause); `note realtime on|off`; `note delete <编号|pause|resume|段名>`.
 */
internal fun VScreenOffloadHandler.noteCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val p = args.positional
    val pkg = noteApp(sid)
    val first = p.firstOrNull()?.lowercase()
    val msg: String = when {
        p.isEmpty() -> return text(noteLines(pkg).joinToString("\n"))
        first == "pause" || first == "resume" -> {
            val body = p.drop(1).joinToString(" ").trim()
            if (body.isEmpty()) throw ActionError("INVALID_ARGS", "note $first \"<步骤>\"，如 note $first \"tap 1840 60\"")
            Steps.parseAll(body)
            VScreenMap.setProfile(pkg, mapOf(first to body))
            val prof = VScreenMap.profile(pkg)
            "已记 $pkg 的${if (first == "pause") "暂停" else "继续"}步骤：$body" +
                if (prof["pause"] != null && prof["resume"] != null) "。暂停和继续都记了：之后每段 act 先“${prof["resume"]}”，做完和观察完再“${prof["pause"]}”（--paused 可跳过）"
                else "。还要记 note ${if (first == "pause") "resume" else "pause"} 才会自动回合制"
        }
        first == "realtime" -> {
            val v = p.getOrNull(1)?.lowercase()
            val on = when (v) { "on", "true", "1", "是" -> true; "off", "false", "0", "否" -> false; else -> throw ActionError("INVALID_ARGS", "note realtime on|off") }
            VScreenMap.setProfile(pkg, mapOf("realtime" to on.toString()))
            if (on) "已记 $pkg 为实时应用：动作之间不等画面稳定，不带步骤的 act 看 1 秒" else "已记 $pkg 不是实时应用"
        }
        first == "delete" || first == "del" || first == "rm" -> {
            val what = p.drop(1).joinToString(" ").trim().ifEmpty { throw ActionError("INVALID_ARGS", "note delete <编号|pause|resume|段名>") }
            when {
                what == "pause" || what == "resume" || what == "realtime" -> { VScreenMap.setProfile(pkg, mapOf(what to "")); "已删 $pkg 的 $what" }
                what.toIntOrNull() != null -> if (VScreenMap.deleteNote(pkg, what.toInt())) "已删笔记 $what" else throw ActionError("NOT_FOUND", "$pkg 没有第 $what 条笔记")
                else -> if (VScreenMap.deleteSegment(pkg, what)) "已删段“$what”" else throw ActionError("NOT_FOUND", "$pkg 没有叫“$what”的笔记编号或段")
            }
        }
        else -> {
            val t = p.joinToString(" ").trim()
            if (t.length > 300) throw ActionError("INVALID_ARGS", "一条笔记最多 300 字（收到 ${t.length}）：写要点")
            val n = VScreenMap.addNote(pkg, t)
            "已记到 $pkg 第 ${n.id} 条（下次 launch 它会列出来）"
        }
    }
    return text(msg + "\n\n" + noteLines(pkg).joinToString("\n"))
}

/** `keep` — the watches kept after turns; `keep off <名字|all>` — stop them. Started by `act --stop … --keep notify|wake`. */
internal fun VScreenOffloadHandler.keepCmd(@Suppress("UNUSED_PARAMETER") sid: String?, args: OffloadArgs): NativeOffloadResult {
    val p = args.positional
    if (p.firstOrNull()?.lowercase() == "off") {
        val name = p.getOrNull(1) ?: throw ActionError("INVALID_ARGS", "keep off <名字|all>")
        // 1.1: ended watches (seen / timed out) stay listed until cleared; off clears them too.
        if (name == "all") {
            val stopped = VScreenRules.stopAll(); val cleared = VScreenRules.clearEnded()
            return text("已停 $stopped 个正在盯的" + if (cleared > 0) "，清掉 $cleared 条已结束的" else "")
        }
        if (VScreenRules.stop(name)) { VScreenRules.clearEnded(name); return text("已停「$name」") }
        if (VScreenRules.clearEnded(name) > 0) return text("「$name」已经结束了，已从列表清掉")
        throw ActionError("NOT_FOUND", "没有叫「$name」的盯守：keep 看列表")
    }
    if (p.isNotEmpty()) throw ActionError("INVALID_ARGS", "keep（列表）| keep off <名字|all>。开始盯守用 act --stop \"条件\" --keep notify|wake [--for 1h]")
    val list = VScreenRules.list()
    if (list.isEmpty()) return text("没有盯守。开始：act --stop \"text:到货\" --keep notify [--for 2h]")
    fun action(l: VScreenRules.Live) = l.json().optJSONArray("action")?.join("、")?.replace("\"", "")
    val running = list.filter { !it.stopped }.map { l ->
        "「${l.spec.name}」${l.spec.describe()} — ${l.status}，还剩 ${l.json().opt("ends_in_min")} 分钟；看到时${action(l)}"
    }
    val ended = list.filter { it.stopped }.map { l ->
        "「${l.spec.name}」${l.spec.describe()} — ${l.endReason ?: "已结束"}"
    }
    return text(buildList {
        if (running.isNotEmpty()) { add("正在盯："); addAll(running) }
        if (ended.isNotEmpty()) { add("已结束（keep off <名字> 或 keep off all 清掉）："); addAll(ended) }
    }.joinToString("\n"))
}

/** Plain text result. */
internal fun text(s: String): NativeOffloadResult = NativeOffloadResult(0, s.trimEnd() + "\n")

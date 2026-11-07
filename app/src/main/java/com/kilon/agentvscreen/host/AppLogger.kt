package com.kilon.agentvscreen.host

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Logcat plus a small rolling file (files/logs/agent-vscreen.log) that `status` and the app can show. */
object AppLogger {
    @Volatile private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "logs").apply { mkdirs() }.let { File(it, "agent-vscreen.log") }
    }

    fun info(tag: String, msg: String) { Log.i(tag, msg); write("I", tag, msg) }
    fun warning(tag: String, msg: String) { Log.w(tag, msg); write("W", tag, msg) }
    fun error(tag: String, msg: String, t: Throwable? = null) { Log.e(tag, msg, t); write("E", tag, msg + (t?.let { " / ${it.javaClass.simpleName}: ${it.message}" } ?: "")) }

    fun tail(lines: Int = 200): String = runCatching { file?.readLines()?.takeLast(lines)?.joinToString("\n") }.getOrNull() ?: ""

    @Synchronized private fun write(level: String, tag: String, msg: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > 1_000_000) { f.renameTo(File(f.parentFile, "agent-vscreen.1.log")) }
            f.appendText("${fmt.format(Date())} $level/$tag: $msg\n")
        }
    }
}

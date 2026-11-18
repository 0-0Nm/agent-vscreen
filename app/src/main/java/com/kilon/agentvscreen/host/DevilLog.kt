package com.kilon.agentvscreen.host

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Separate opt-in stream; event payloads must never contain application content. */
internal object DevilLog {
    enum class Event { CREATED, ADOPTED, PARK, RESUME, RELEASE, ACQUIRE_FAILURE, CONVERT_FAILURE, WAKE, LAYOUT, MANUAL_CONNECT, MANUAL_OPEN, MANUAL_APP }
    enum class Alignment { PAGE_CHANGED, SENSITIVE_CONTENT, TREE_TRUNCATED, NO_FRAME, DISPLAY_NOT_ON, COORDINATE_SPACE_MISMATCH, CACHED_FRAME, FRAME_CHANGED_DURING_CAPTURE, BEST_EFFORT, MIXED_WINDOWS, NO_FRAME_COPY, UNKNOWN }
    enum class Failure { STATE, ARGUMENT, SECURITY, MEMORY, OTHER }
    private val gate = Any()
    @Volatile private var enabled = false
    private var epoch = 0L
    private var prefs: SharedPreferences? = null
    private var file: File? = null
    private val last = HashMap<Event, Long>()
    private val worker by lazy { ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { r -> Thread(r,"atvsx-devil-log").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardPolicy()) }

    fun init(ctx: Context) = synchronized(gate) {
        if (prefs != null) return@synchronized
        prefs = ctx.applicationContext.getSharedPreferences("atvsx_diagnostics", Context.MODE_PRIVATE)
        file = File(ctx.applicationContext.filesDir, "devil-log/events.log")
        enabled = prefs?.getBoolean("devil_log", false) ?: false
    }
    fun isEnabled() = enabled
    fun setEnabled(on: Boolean) = synchronized(gate) {
        if (prefs == null || enabled == on) return@synchronized
        epoch++; enabled = on; last.clear()
        prefs?.edit()?.putBoolean("devil_log", on)?.apply()
    }
    fun record(event: Event, vararg fields: Long) {
        if (!enabled) return
        val time = android.os.SystemClock.elapsedRealtime()
        val stamp = synchronized(gate) {
            if (!enabled) return
            if (event == Event.ACQUIRE_FAILURE || event == Event.CONVERT_FAILURE || event == Event.WAKE) {
                if (last[event]?.let { time - it < 5_000 } == true) return
                last[event] = time
            }
            epoch
        }
        val line = encode(System.currentTimeMillis(), event, fields)
        runCatching {
            worker.execute {
                synchronized(gate) {
                    if (enabled && epoch == stamp) file?.let { runCatching { appendBounded(it, line) } }
                }
            }
        }
    }
    fun tail(lines: Int): String = synchronized(gate) {
        if (!enabled) return@synchronized ""
        val f = file ?: return@synchronized ""
        runCatching {
            listOf(File(f.parentFile, "events.1.log"), f).flatMap {
                if (it.isFile && it.length() <= LIMIT) it.readLines() else emptyList()
            }.takeLast(lines.coerceIn(1, 100)).joinToString("\n")
        }.getOrDefault("")
    }
    fun failure(t: Throwable): Long = when (t) {
        is IllegalStateException -> Failure.STATE
        is IllegalArgumentException -> Failure.ARGUMENT
        is SecurityException -> Failure.SECURITY
        is OutOfMemoryError -> Failure.MEMORY
        else -> Failure.OTHER
    }.ordinal.toLong()
    fun layout(display: Int, count: Int, truncated: Boolean, reason: String) {
        if (!enabled) return
        val alignment = Alignment.entries.firstOrNull { it.name == reason } ?: Alignment.UNKNOWN
        record(Event.LAYOUT, display.toLong(), count.toLong(), if (truncated) 1L else 0L, alignment.ordinal.toLong())
    }
    internal const val LIMIT = 128 * 1024L
    internal fun encode(time: Long, event: Event, fields: LongArray): String =
        "$time ${event.name} " + fields.take(20).joinToString(" ") + "\n"
    internal fun appendBounded(f: File, line: String, limit: Long = LIMIT) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size > limit) return
        f.parentFile?.mkdirs()
        if (f.length() + bytes.size > limit) {
            if (!f.renameTo(File(f.parentFile, "events.1.log"))) return
        }
        f.appendBytes(bytes)
    }
}

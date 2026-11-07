package com.kilon.agentvscreen.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.kilon.agentvscreen.host.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * [vscreen] v42: the background screen's recording track.
 *
 * While a chat uses the background screen, the perception service hands every NEW frame it looks at
 * (~5 a second, only when the picture changed) to this recorder, which keeps it as a small JPEG on disk
 * with the time, plus a mark for every action (tap dot, drag line) and every event. The agent can then
 * look at ANY moment — above all the seconds it spent thinking or calling other tools — with `rewind`,
 * and action films (`--film`, run) are cut from the same track (one source for what the screen showed).
 *
 * Kept: the last 3 minutes at full rate, minutes 3–10 at one frame a second, at most 60 MB; deleted when
 * the screen is released. Off (settings › 后台录屏, or `map set record=false` for an app): only the last
 * 15 seconds are kept, for films and pause checks.
 */
object VScreenRecorder {
    private const val TAG = "VScreenRecorder"
    const val MAX_SIDE = 640
    private const val MIN_GAP_MS = 150L
    private const val FULL_MS = 3 * 60_000L
    private const val KEEP_MS = 10 * 60_000L
    private const val OFF_KEEP_MS = 15_000L
    private const val MAX_BYTES = 60L * 1024 * 1024

    class Frame(val atMs: Long, val file: File, val bytes: Long)

    /** Something drawn on the track: an action (with its points) or an event. */
    class Mark(val n: Int, val atMs: Long, val kind: String, val label: String, val pts: List<IntArray>) {
        fun json(t0: Long? = null): JSONObject = JSONObject().put("n", n).put("kind", kind).put("label", label)
            .also { o -> if (t0 != null) o.put("t_s", ((atMs - t0) / 100) / 10.0) else o.put("at_ms", atMs) }
            .also { o -> if (pts.isNotEmpty()) o.put("points", JSONArray(pts.map { JSONArray(listOf(it[0], it[1])) })) }
    }

    private class Track(val displayId: Int, val dir: File, val frameW: Int, val frameH: Int) {
        val frames = ArrayDeque<Frame>()
        val marks = ArrayDeque<Mark>()
        var bytes = 0L
        var lastVersion = -1L
        var lastAt = 0L
        var markSeq = 0
        @Volatile var on = true
        val startedAt = System.currentTimeMillis()
    }

    private val tracks = HashMap<Int, Track>()

    private fun root(): File? = BackgroundOperation.appContextOrNull()?.cacheDir?.let { File(it, "vscreen-rec") }

    private fun track(d: VScreenDisplay): Track? = synchronized(tracks) {
        tracks[d.displayId]?.takeIf { it.frameW == d.width && it.frameH == d.height }?.let { return it }
        tracks.remove(d.displayId)?.let { drop(it) }
        val dir = File(root() ?: return null, "${d.displayId}-${d.generation}-${System.currentTimeMillis()}").apply { mkdirs() }
        Track(d.displayId, dir, d.width, d.height).also { tracks[d.displayId] = it }
    }

    private fun existing(displayId: Int): Track? = synchronized(tracks) { tracks[displayId] }

    /** The global switch (settings) and the app's profile key record=false. */
    fun enabledFor(pkg: String?): Boolean = BackgroundOperation.state.value.recording &&
        (pkg == null || runCatching { VScreenMap.profile(pkg)["record"] != "false" }.getOrDefault(true))

    /** A frame the perception service just looked at; stored only when it is new. Never throws. */
    fun offer(d: VScreenDisplay, frame: Bitmap, version: Long, pkg: String?) {
        runCatching {
            val t = track(d) ?: return
            val now = System.currentTimeMillis()
            if (version == t.lastVersion || now - t.lastAt < MIN_GAP_MS) return
            t.lastVersion = version; t.lastAt = now
            t.on = enabledFor(pkg)
            val k = MAX_SIDE.toDouble() / maxOf(frame.width, frame.height)
            val small = if (k >= 1.0) frame else Bitmap.createScaledBitmap(frame, maxOf(1, (frame.width * k).roundToInt()), maxOf(1, (frame.height * k).roundToInt()), true)
            val f = File(t.dir, "$now.jpg")
            FileOutputStream(f).use { small.compress(Bitmap.CompressFormat.JPEG, 60, it) }
            if (small !== frame) small.recycle()
            synchronized(t) {
                t.frames.addLast(Frame(now, f, f.length())); t.bytes += f.length()
                prune(t, now)
            }
        }.onFailure { AppLogger.warning(TAG, "offer: ${it.message}") }
    }

    /** Thin out old frames: one a second after 3 minutes, nothing after 10, never above 60 MB. */
    private fun prune(t: Track, now: Long) {
        val keep = if (t.on) KEEP_MS else OFF_KEEP_MS
        fun del(fr: Frame) { fr.file.delete(); t.bytes -= fr.bytes }
        while (t.frames.isNotEmpty() && (now - t.frames.first().atMs > keep || t.bytes > MAX_BYTES)) del(t.frames.removeFirst())
        if (t.on) {
            // Thin frames older than FULL_MS to one per second (only look at the part that just crossed the line).
            var lastKept = -1L
            val it = t.frames.iterator()
            val thinned = ArrayList<Frame>()
            while (it.hasNext()) {
                val fr = it.next()
                if (now - fr.atMs <= FULL_MS) break
                if (lastKept >= 0 && fr.atMs - lastKept < 1_000L) { thinned += fr } else lastKept = fr.atMs
            }
            if (thinned.isNotEmpty()) { t.frames.removeAll(thinned.toSet()); thinned.forEach { del(it) } }
        }
        while (t.marks.isNotEmpty() && now - t.marks.first().atMs > keep) t.marks.removeFirst()
    }

    /** Put an action / event on the track. Returns its number (action:N / event:N for rewind --around). */
    fun mark(displayId: Int, kind: String, label: String, pts: List<IntArray> = emptyList(), atMs: Long = System.currentTimeMillis()): Int {
        val t = existing(displayId) ?: return 0
        synchronized(t) {
            // v44: every logged action and read gets its own number (log's n); events take the number of the action before them.
            val n = if (kind == "action" || kind == "read") ++t.markSeq else t.markSeq
            t.marks.addLast(Mark(n, atMs, kind, label.take(80), pts))
            while (t.marks.size > 2_000) t.marks.removeFirst()
            return n
        }
    }

    /** v47: the frame version of the newest frame on the track (null: no track). */
    fun lastVersion(displayId: Int): Long? = existing(displayId)?.let { t -> synchronized(t) { t.lastVersion } }

    fun frames(displayId: Int, fromMs: Long, toMs: Long): List<Frame> = existing(displayId)?.let { t ->
        synchronized(t) { t.frames.filter { it.atMs in fromMs..toMs } }
    }.orEmpty()

    /** The frame showing at [atMs]: the last one taken at or before it (the picture does not change between frames). */
    fun frameAt(displayId: Int, atMs: Long): Frame? = existing(displayId)?.let { t ->
        synchronized(t) { t.frames.lastOrNull { it.atMs <= atMs } ?: t.frames.firstOrNull() }
    }

    fun marks(displayId: Int, fromMs: Long, toMs: Long): List<Mark> = existing(displayId)?.let { t ->
        synchronized(t) { t.marks.filter { it.atMs in fromMs..toMs } }
    }.orEmpty()

    fun actionMark(displayId: Int, n: Int): Mark? = existing(displayId)?.let { t -> synchronized(t) { t.marks.lastOrNull { (it.kind == "action" || it.kind == "read") && it.n == n } } }

    /** v44: the points of action [n] (known only once the command has finished: where it tapped / dragged). */
    fun setPoints(displayId: Int, n: Int, pts: List<IntArray>) {
        val t = existing(displayId) ?: return
        synchronized(t) {
            val i = t.marks.indexOfLast { it.kind == "action" && it.n == n }
            if (i >= 0) { val m = t.marks[i]; t.marks[i] = Mark(m.n, m.atMs, m.kind, m.label, pts) }
        }
    }

    /** Frame size of the track's display (marks are in these pixels). */
    fun frameSize(displayId: Int): Pair<Int, Int>? = existing(displayId)?.let { it.frameW to it.frameH }

    /** Decode a kept frame, at most [maxSide] px on its long side. */
    fun decode(f: Frame, maxSide: Int = MAX_SIDE): Bitmap? = runCatching {
        // v0.5: let the JPEG decoder scale down (DCT scaling) instead of decoding full size and scaling after.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.file.absolutePath, bounds)
        var sample = 1
        val side = maxOf(bounds.outWidth, bounds.outHeight)
        while (side > 0 && side / (sample * 2) >= maxSide) sample *= 2
        val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inSampleSize = sample }
        val b = BitmapFactory.decodeFile(f.file.absolutePath, o) ?: return null
        val k = maxSide.toDouble() / maxOf(b.width, b.height)
        if (k >= 1.0) b else Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).roundToInt()), maxOf(1, (b.height * k).roundToInt()), true).also { if (it !== b) b.recycle() }
    }.getOrNull()

    /** Status: is it recording, how much is kept. */
    fun status(displayId: Int?): JSONObject {
        val t = displayId?.let { existing(it) }
        val o = JSONObject().put("enabled", BackgroundOperation.state.value.recording)
        if (t == null) return o.put("recording", false)
        return synchronized(t) {
            o.put("recording", true).put("full", t.on).put("frames", t.frames.size)
                .put("seconds", t.frames.firstOrNull()?.let { ((System.currentTimeMillis() - it.atMs) / 1000) } ?: 0)
                .put("mb", ((t.bytes / 1024.0 / 1024.0) * 10).roundToInt() / 10.0)
                .put("note", if (t.on) "后台录屏：最近 3 分钟全速、3–10 分钟每秒一帧（rewind 查看）" else "录屏关着：只留最近 15 秒给 film / 暂停核对")
        }
    }

    /** The screen is gone: delete its track. */
    fun stop(displayId: Int) { synchronized(tracks) { tracks.remove(displayId) }?.let { drop(it) } }

    private fun drop(t: Track) { runCatching { t.dir.deleteRecursively() } }

    /** At startup: tracks of a previous process are stale. */
    fun clearAll() { runCatching { root()?.deleteRecursively() } }
}

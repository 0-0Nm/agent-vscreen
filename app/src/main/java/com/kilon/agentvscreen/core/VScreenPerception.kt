package com.kilon.agentvscreen.core

import android.graphics.Bitmap
import android.os.SystemClock
import com.kilon.agentvscreen.host.AppLogger

/**
 * [vscreen] v49: the perception thread keeps LOOKING, it does not judge.
 *
 * While a chat works on the background screen, a small thread looks at it about 5 times a second: every new
 * frame goes to the recording track ([VScreenRecorder]), and about once a second (sooner when the picture
 * changed, or when someone asks) the words on it are read (OCR + the accessibility texts) and kept with the time.
 * That is all: what appeared, what went, what moved is told by the act return from these readings and the track
 * (facts), and what it means is the model's call. There are no event kinds, no progress, no pulses, no background
 * map any more. It stops by itself 5 minutes after the last command.
 */
object VScreenPerception {
    private const val TAG = "VScreenPerception"
    private const val PERIOD_MS = 200L
    private const val IDLE_STOP_MS = 300_000L
    private const val MAX_READINGS = 900
    const val BLANK_MS = 1_500L

    /** The words on the screen at one moment (epoch ms), as written there (trimmed), de-duplicated. */
    class Reading(val atMs: Long, val words: List<String>)

    private class Watcher(val d: VScreenDisplay) {
        @Volatile var running = true
        @Volatile var lastUse = SystemClock.elapsedRealtime()
        val readings = ArrayDeque<Reading>()
        @Volatile var reading: VScreenOcr.Result? = null
        @Volatile var readingAt = 0L            // elapsedRealtime of the frame behind [reading]
        @Volatile var readingVersion = -1L      // frame version behind [reading]
        @Volatile var readingSmall: Bitmap? = null  // small copy of that frame (is the picture still the same?)
        @Volatile var forceOcr = false
        @Volatile var ocrMs = 0L
        @Volatile var ocrEngine: String = VScreenOcr.ENGINE
        @Volatile var blankSince = 0L
        val lock = Object()
        var thread: Thread? = null
    }

    private val watchers = HashMap<Int, Watcher>()

    /** Start (or keep alive) the watcher for [d]. Cheap to call on every command. */
    fun ensure(d: VScreenDisplay) {
        if (d.released) return
        synchronized(watchers) {
            val w = watchers[d.displayId]
            if (w != null && w.running && w.d === d) { w.lastUse = SystemClock.elapsedRealtime(); return }
            w?.running = false
            val nw = Watcher(d)
            watchers[d.displayId] = nw
            nw.thread = Thread { runCatching { loop(nw) }.onFailure { AppLogger.warning(TAG, "loop: ${it.message}") }; nw.running = false }
                .apply { isDaemon = true; name = "vscreen-perception-${d.displayId}"; priority = Thread.MIN_PRIORITY; start() }
        }
    }

    fun stop(displayId: Int) { synchronized(watchers) { watchers.remove(displayId)?.running = false } }

    private fun watcher(displayId: Int): Watcher? = synchronized(watchers) { watchers[displayId]?.takeIf { it.running } }

    fun running(displayId: Int): Boolean = watcher(displayId) != null

    /** When the frame of the latest reading was taken (elapsedRealtime; 0 = none). */
    fun readingTime(displayId: Int): Long = watcher(displayId)?.readingAt ?: 0L

    /**
     * The latest OCR reading of a frame taken at or after [notBefore] (elapsedRealtime); when the latest is older a
     * new one is asked for and awaited up to [waitMs]. null = none new enough (yet).
     */
    internal fun reading(displayId: Int, notBefore: Long, waitMs: Long): VScreenOcr.Result? {
        val w = watcher(displayId) ?: return null
        fun ok() = w.reading != null && w.readingAt >= notBefore
        if (ok()) return w.reading
        w.forceOcr = true
        if (waitMs > 0) {
            val t = SystemClock.elapsedRealtime()
            synchronized(w.lock) { while (w.running && !ok() && SystemClock.elapsedRealtime() - t < waitMs) w.lock.wait(100L) }
        }
        return if (ok()) w.reading else null
    }

    /**
     * v0.5: the latest reading when it was taken of exactly this picture: the same frame [version], or a frame that
     * looks the same (no sampled pixel differs). Null = the words of [frame] are not known yet.
     */
    internal fun readingFor(displayId: Int, frame: Bitmap, version: Long): VScreenOcr.Result? {
        val w = watcher(displayId) ?: return null
        val (r, v, sm) = synchronized(w.lock) { Triple(w.reading, w.readingVersion, w.readingSmall) }
        if (r == null || r.error != null) return null
        if (v == version) return r
        if (sm == null) return null
        val cur = small(frame)
        return try {
            synchronized(w.lock) {
                if (sm.isRecycled || sm.width != cur.width || sm.height != cur.height) null
                else if (VPixels.regionDiff(sm, cur, android.graphics.Rect(0, 0, cur.width, cur.height)) in 0.0..0.0005) r else null
            }
        } finally { cur.recycle() }
    }

    /** v0.5: a reading someone else made of [frame] becomes the watcher's latest (no second OCR of the same picture). */
    internal fun offerReading(displayId: Int, frame: Bitmap, version: Long, r: VScreenOcr.Result, a11y: List<String>) {
        val w = watcher(displayId) ?: return
        if (r.error != null) return
        val words = if (VScreenActivity.uniform(frame)) emptyList() else wordsOf(r.items, a11y)
        val sm = small(frame)
        synchronized(w.lock) {
            if (w.readingVersion > version) { sm.recycle(); return }
            w.reading = r; w.readingAt = SystemClock.elapsedRealtime(); w.readingVersion = version
            w.readingSmall?.recycle(); w.readingSmall = sm
            w.forceOcr = false
            val last = w.readings.lastOrNull()
            if (last == null || last.words != words) {
                w.readings.addLast(Reading(System.currentTimeMillis(), words))
                while (w.readings.size > MAX_READINGS) w.readings.removeFirst()
            }
            w.lock.notifyAll()
        }
        VScreenOcr.remember(displayId, r.items)
    }

    /** Ask for a reading as soon as possible (a text condition is being waited for). */
    fun hurry(displayId: Int) { watcher(displayId)?.forceOcr = true }

    /** The readings kept between two epoch times (oldest first), plus the last one before [fromMs] first when there is one. */
    fun readings(displayId: Int, fromMs: Long, toMs: Long): List<Reading> = watcher(displayId)?.let { w ->
        synchronized(w.lock) {
            val before = w.readings.lastOrNull { it.atMs < fromMs }
            listOfNotNull(before) + w.readings.filter { it.atMs in fromMs..toMs }
        }
    }.orEmpty()

    /** The newest reading's words (null: none yet). */
    fun words(displayId: Int): Reading? = watcher(displayId)?.let { w -> synchronized(w.lock) { w.readings.lastOrNull() } }

    /** How long the picture has been one flat colour, when that is at least [BLANK_MS]; null = it has content. */
    fun blankFor(displayId: Int): Long? = watcher(displayId)?.blankSince?.takeIf { it > 0 }
        ?.let { SystemClock.elapsedRealtime() - it }?.takeIf { it >= BLANK_MS }

    fun engine(displayId: Int): String? = watcher(displayId)?.let { it.ocrEngine + if (it.ocrMs > 0) "（约 ${it.ocrMs}ms/次）" else "" }

    /** The words of one reading: OCR lines (no junk) + accessibility texts, trimmed, one each by normalised form. */
    internal fun wordsOf(items: List<VScreenOcr.Item>, a11y: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (t in items.filter { !VScreenOcr.isJunk(it) }.map { it.text.replace('\n', ' ').trim() } + a11y.map { it.replace('\n', ' ').trim() }) {
            if (t.isEmpty() || t.length > 80) continue
            if (seen.add(VScreenOcr.norm(t))) out += t
        }
        return out
    }

    private fun loop(w: Watcher) {
        val d = w.d
        var lastOcr = 0L
        var lastVersion = -1L
        var changedSinceOcr = true
        var pkg: String? = null; var pkgAt = 0L
        while (w.running && !d.released && SystemClock.elapsedRealtime() - w.lastUse < IDLE_STOP_MS) {
            val t0 = SystemClock.elapsedRealtime()
            if (d.parked) { Thread.sleep(500); continue }
            d.requestFreshFrame()
            d.awaitFrameAfter(t0, 300)
            val fv = d.latestWithVersion()
            if (fv == null) { Thread.sleep(PERIOD_MS); continue }
            val now = SystemClock.elapsedRealtime()
            if (now - pkgAt > 5_000L || pkg == null) { pkg = VScreenShell.topPackage(d.displayId); pkgAt = now }
            VScreenRecorder.offer(d, fv.first, fv.second, pkg)
            if (fv.second != lastVersion) { changedSinceOcr = fv.second != w.readingVersion; lastVersion = fv.second }
            val uniform = VScreenActivity.uniform(fv.first)
            if (uniform) { if (w.blankSince == 0L) w.blankSince = now } else w.blankSince = 0L
            val forced = w.forceOcr
            val gap = maxOf(1_000L, w.ocrMs * 2)
            if (forced || (now - lastOcr >= gap && (changedSinceOcr || now - lastOcr >= 5_000L))) {
                lastOcr = now; w.forceOcr = false; changedSinceOcr = false
                val frameAt = now
                val r = runCatching { VScreenOcr.recognize(d.displayId, fv.first, fv.second, accurate = VScreenOcr.accurateEnabled()) }.getOrNull()
                if (r != null) { w.ocrMs = r.ms; w.ocrEngine = r.engine }
                if (r != null && r.error == null) {
                    val a11y = runCatching { BackgroundOperation.a11yTexts(d.displayId, 200) }.getOrNull().orEmpty()
                    val words = if (uniform) emptyList() else wordsOf(r.items, a11y)
                    val sm = small(fv.first)
                    synchronized(w.lock) {
                        w.reading = r; w.readingAt = frameAt; w.readingVersion = fv.second
                        w.readingSmall?.recycle(); w.readingSmall = sm
                        val last = w.readings.lastOrNull()
                        if (last == null || last.words != words) {
                            w.readings.addLast(Reading(System.currentTimeMillis(), words))
                            while (w.readings.size > MAX_READINGS) w.readings.removeFirst()
                        }
                        w.lock.notifyAll()
                    }
                    VScreenOcr.remember(d.displayId, r.items)
                }
            }
            synchronized(w.lock) { w.lock.notifyAll() }
            val left = PERIOD_MS - (SystemClock.elapsedRealtime() - t0)
            if (left > 0) Thread.sleep(left)
        }
        w.running = false
        synchronized(w.lock) { w.lock.notifyAll() }
    }

    /** Small copy of [f] (long side [side]) for pixel comparisons; never the frame itself. */
    fun small(f: Bitmap, side: Int = 360): Bitmap {
        val k = side.toDouble() / maxOf(f.width, f.height)
        if (k >= 1.0) return f.copy(Bitmap.Config.ARGB_8888, false)
        val s = Bitmap.createScaledBitmap(f, maxOf(1, (f.width * k).toInt()), maxOf(1, (f.height * k).toInt()), true)
        return if (s === f) f.copy(Bitmap.Config.ARGB_8888, false) else s
    }
}

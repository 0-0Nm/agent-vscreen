package com.kilon.agentvscreen.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.kilon.agentvscreen.host.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [vscreen] One agent-owned virtual display plus its frame pump.
 *
 * Surface contract (learned from the OpenMinis-Linux vscreen work): the
 * display is created WITH an ImageReader surface and keeps it for its whole
 * lifetime. A surface-less virtual display is put to sleep by WMS, which
 * hides its windows and drops injected input.
 *
 * Frame contract (the reason this class exists instead of reading the
 * reader only on demand): the reader is drained on EVERY frame by a
 * dedicated HandlerThread, whether or not anybody is looking. Holding
 * images in the reader's queue stalls the producer, so the display stops
 * updating and eventually sleeps. Collapsing the chat preview therefore only
 * stops the UI from redrawing; it never stops this pump. The pump converts
 * at most one frame per [minFrameIntervalMs] into a Bitmap that the
 * preview and the agent's screenshots share.
 *
 * Flags: PUBLIC | OWN_CONTENT_ONLY. PUBLIC lets the shell (`am start
 * --display`) put other apps' activities on it and lets the accessibility
 * framework track its windows; OWN_CONTENT_ONLY keeps it from mirroring the
 * user's screen and needs no CAPTURE_VIDEO_OUTPUT permission. If the ROM
 * refuses a public display we retry private and say so in [note].
 */
class VScreenDisplay private constructor(
    val displayId: Int,
    /** Size of the surface (natural orientation). The agent works in [width] x [height]. */
    val physWidth: Int,
    val physHeight: Int,
    val dpi: Int,
    /** Monotonic per-process counter; a late action aimed at an older screen is refused. */
    val generation: Long,
    val isPublic: Boolean,
    private val virtualDisplay: VirtualDisplay?,
    private val reader: ImageReader,
    private val thread: HandlerThread,
    /** Created by the Shizuku shell service: owned by the shell uid, so `am start --display` may use it. */
    val shellOwned: Boolean = false,
    /** VIRTUAL_DISPLAY_FLAG_TRUSTED was granted (Android 13+ shell display). */
    val trusted: Boolean = false,
    /** VIRTUAL_DISPLAY_FLAG_* actually granted (0 = unknown / app-owned fallback). */
    val flags: Int = 0,
) {
    // ── orientation ─────────────────────────────────────────────────────────
    // The display is created ROTATES_WITH_CONTENT: when a landscape app (a game) asks for
    // landscape, the LOGICAL display rotates (input and the app use 2800x1264) while the surface
    // stays 1264x2800, so the picture arrives turned by 90 degrees. Every frame is turned back
    // here, so OCR, vision, screenshots, the preview and all coordinates are in the logical,
    // upright space, which is also the space injected input uses. Nobody does rotation maths.

    /** Logical rotation of the display (Surface.ROTATION_*), from DisplayManager or the shell. */
    @Volatile var rotation: Int = 0
        private set
    /** Calibration: the default turn for this rotation came out upside down (text check). */
    @Volatile var flip180: Boolean = false
    /** Calibration: this system letterboxes the rotated content upright instead of turning it (no turn needed). */
    @Volatile var noTurn: Boolean = false
    /** Fixed turn in degrees set by `screen rotate`, or null for automatic. */
    @Volatile var turnOverride: Int? = null
    /** Rotations whose direction has been checked against on-screen text. */
    val calibrated = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    /** Called (any thread) when the logical size changes. */
    @Volatile var onGeometryChanged: (() -> Unit)? = null

    /** Clockwise degrees applied to each surface frame to make it upright. */
    val bufferTurn: Int get() {
        turnOverride?.let { return ((it % 360) + 360) % 360 }
        if (noTurn) return 0
        val base = when (rotation) { 1 -> 270; 2 -> 180; 3 -> 90; else -> 0 }
        return if (flip180 && base != 0) (base + 180) % 360 else base
    }
    /** Logical (agent / input) width and height. */
    val width: Int get() = if (bufferTurn % 180 != 0) physHeight else physWidth
    val height: Int get() = if (bufferTurn % 180 != 0) physWidth else physHeight

    /** Size of the display as the system and injected input see it (rotates with the content). 0 = unknown. */
    @Volatile var inputWidth: Int = 0
        private set
    @Volatile var inputHeight: Int = 0
        private set

    /** [r] = Surface.ROTATION_*, [lw]x[lh] = the logical size the system reports (0 = derive from [r]). */
    fun setRotation(r: Int, lw: Int = 0, lh: Int = 0) {
        val n = ((r % 4) + 4) % 4
        val nw = if (lw > 0) lw else if (n % 2 == 1) physHeight else physWidth
        val nh = if (lh > 0) lh else if (n % 2 == 1) physWidth else physHeight
        if (n == rotation && nw == inputWidth && nh == inputHeight) return
        val oldW = width; val oldR = rotation
        rotation = n; inputWidth = nw; inputHeight = nh
        if (n != oldR) requestFreshFrame()
        if (width != oldW || n != oldR) onGeometryChanged?.invoke()
    }

    /**
     * Frame (agent) pixel -> input pixel. Identity when the upright frame and the logical display
     * have the same size (the normal case); otherwise the logical display is fitted into the
     * frame (letterbox) and the point is mapped back through that fit.
     */
    fun toInput(x: Float, y: Float): Pair<Float, Float> {
        val iw = inputWidth.takeIf { it > 0 } ?: width
        val ih = inputHeight.takeIf { it > 0 } ?: height
        val fw = width; val fh = height
        if (iw == fw && ih == fh) return x to y
        val s = minOf(fw.toFloat() / iw, fh.toFloat() / ih)
        val ox = (fw - iw * s) / 2f; val oy = (fh - ih * s) / 2f
        return ((x - ox) / s).coerceIn(0f, iw - 1f) to ((y - oy) / s).coerceIn(0f, ih - 1f)
    }

    /** Human description of frame space vs input space, for results and `screen info`. */
    fun geometryJson(): org.json.JSONObject {
        val iw = inputWidth.takeIf { it > 0 } ?: width
        val ih = inputHeight.takeIf { it > 0 } ?: height
        val identity = iw == width && ih == height
        return org.json.JSONObject()
            .put("display_rotation", rotation * 90)
            .put("surface", "${physWidth}x$physHeight")
            .put("frame_space", "${width}x$height（已转正，read/look/截图/tap 的坐标都在这里）")
            .put("input_space", "${iw}x$ih（系统注入触摸用的逻辑坐标）")
            .put("frame_turn_deg", bufferTurn)
            .put("turn_source", if (turnOverride != null) "screen rotate 手动" else if (flip180) "自动（文字校准后翻转 180°）" else "自动")
            .put("transform", if (identity) "identity：画面坐标 = 输入坐标，工具不需要也不会再换算"
                else "letterbox：逻辑屏 ${iw}x$ih 缩放后居中放在 ${width}x$height 画面里，工具自动逆变换")
    }

    private var listener: DisplayManager.DisplayListener? = null

    /** Follow rotation through DisplayManager (works when the display is PUBLIC). False if not visible. */
    fun watchRotation(context: Context): Boolean {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return false
        val disp = runCatching { dm.getDisplay(displayId) }.getOrNull() ?: return false
        applyDisplay(disp)
        if (listener == null) {
            val l = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(id: Int) {}
                override fun onDisplayRemoved(id: Int) {}
                override fun onDisplayChanged(id: Int) {
                    if (id == displayId) runCatching { dm.getDisplay(id) }.getOrNull()?.let { applyDisplay(it) }
                }
            }
            listener = l
            runCatching { dm.registerDisplayListener(l, pumpHandler ?: Handler(thread.looper)) }
            watchContext = context.applicationContext
        }
        return true
    }
    private var watchContext: Context? = null

    private fun applyDisplay(disp: android.view.Display) {
        val pt = android.graphics.Point()
        @Suppress("DEPRECATION") runCatching { disp.getRealSize(pt) }
        setRotation(disp.rotation, pt.x, pt.y)
    }

    /** The display has its own input focus (Android 14+ OWN_FOCUS): key events reach it without stealing focus. */
    val ownFocus: Boolean get() = flags and (1 shl 14) != 0

    @Volatile private var latestRaw: Bitmap? = null
    /** Turn applied to [latestRaw]. */
    @Volatile private var latestTurn = 0
    /**
     * The newest frame, turned the way [bufferTurn] says NOW: when the turn changes while the
     * screen is static (no new frame arrives) the kept frame is re-turned here, once.
     */
    private val latest: Bitmap?
        get() {
            val b = latestRaw ?: return null
            val want = bufferTurn
            if (want == latestTurn) return b
            synchronized(frameLock) {
                val cur = latestRaw ?: return null
                if (want == latestTurn) return cur
                val m = android.graphics.Matrix().apply { postRotate(((want - latestTurn + 360) % 360).toFloat()) }
                val t = runCatching { Bitmap.createBitmap(cur, 0, 0, cur.width, cur.height, m, false) }.getOrNull() ?: return cur
                latestRaw = t; latestTurn = want
                _frameVersion.value = _frameVersion.value + 1
                return t
            }
        }
    @Volatile private var lastConvertMs = 0L
    /** Metadata published together with the converted bitmap under [frameLock]. */
    @Volatile private var latestReceivedAtMs = 0L
    @Volatile private var latestConvertedAtMs = 0L
    @Volatile var released = false
        private set

    /** Frame counter; the preview collects it to know when to redraw. */
    private val _frameVersion = MutableStateFlow(0L)
    val frameVersion: StateFlow<Long> = _frameVersion.asStateFlow()

    @Volatile var minFrameIntervalMs: Long = DEFAULT_FRAME_INTERVAL_MS

    /** Until this elapsed-realtime, frames are converted at ~30 fps (held fingers, watch, fast reactions). */
    @Volatile var boostUntilMs: Long = 0L
    fun boost(ms: Long) { boostUntilMs = maxOf(boostUntilMs, SystemClock.elapsedRealtime() + ms) }
    private val frameInterval: Long get() = if (SystemClock.elapsedRealtime() < boostUntilMs) minOf(33L, minFrameIntervalMs) else minFrameIntervalMs

    /** Elapsed-realtime of the last frame the producer delivered, 0 = none yet. */
    @Volatile var lastFrameAtMs: Long = 0L
        private set

    /** Pump-thread only: the newest frame skipped by the throttle, converted when the throttle opens. */
    private var pending: android.media.Image? = null
    private var pumpHandler: Handler? = null
    private val flushPending = Runnable {
        val p = pending ?: return@Runnable
        pending = null
        convert(p)
    }

    private fun startPump() {
        val h = Handler(thread.looper).also { pumpHandler = it }
        reader.setOnImageAvailableListener({ r ->
            // Always acquire, and never hold more than one image (the pending one):
            // the queue must never fill up.
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val now = SystemClock.elapsedRealtime()
            lastFrameAtMs = now
            val wait = frameInterval - (now - lastConvertMs)
            if (wait > 0 && latest != null) {
                // Throttled. Keep this frame as the trailing one instead of dropping it:
                // otherwise the LAST frame of an animation (a list snapping back, a page
                // sliding in) can be lost and a static screen keeps showing a mid-animation
                // picture (e.g. two stacked title bars) until something else redraws.
                runCatching { pending?.close() }
                pending = img
                h.removeCallbacks(flushPending)
                h.postDelayed(flushPending, wait)
                return@setOnImageAvailableListener
            }
            h.removeCallbacks(flushPending)
            runCatching { pending?.close() }
            pending = null
            convert(img)
        }, h)
    }

    /** Convert [img] into the shared bitmap and close it. Pump thread only. */
    private fun convert(img: android.media.Image) {
        try {
            if (released) return
            val convertedAt = SystemClock.elapsedRealtime()
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * img.width
            val padded = Bitmap.createBitmap(
                img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888,
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            val cut = if (padded.width == img.width) padded
            else Bitmap.createBitmap(padded, 0, 0, img.width, img.height).also { padded.recycle() }
            val turn = bufferTurn
            val exact = if (turn == 0) cut else {
                val m = android.graphics.Matrix().apply { postRotate(turn.toFloat()) }
                Bitmap.createBitmap(cut, 0, 0, cut.width, cut.height, m, false).also { if (it !== cut) cut.recycle() }
            }
            // The old bitmap is NOT recycled: a preview frame may still be
            // drawing it. Letting the GC reclaim it is the safe choice.
            synchronized(frameLock) {
                latestRaw = exact
                latestTurn = turn
                latestReceivedAtMs = lastFrameAtMs
                latestConvertedAtMs = convertedAt
                lastConvertMs = convertedAt
                _frameVersion.value = _frameVersion.value + 1
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "frame convert failed on display $displayId: ${t.message}")
        } finally {
            runCatching { img.close() }
        }
    }

    private val frameLock = Any()

    /** The most recent converted frame, or null before the first frame. Do not recycle it. */
    fun latestFrame(): Bitmap? = latest

    // ── v21: power saving ───────────────────────────────────────────────────

    /** The display has no surface: the system turns it off and stops the apps on it (pages are kept). */
    @Volatile var parked = false
        private set

    /** Detach the surface. False when it could not be done (the screen keeps running). */
    fun park(): Boolean {
        if (released || parked) return parked
        val ok = if (shellOwned) VScreenRemoteDisplay.park(displayId).isSuccess
        else runCatching { virtualDisplay?.surface = null; virtualDisplay != null }.getOrDefault(false)
        if (ok) parked = true
        return ok
    }

    /** Give the surface back and wait (max ~3 s) for the first new frame. Returns a short note. */
    fun unpark(): String {
        if (!parked || released) { parked = false; return "副屏在运行" }
        val t0 = SystemClock.elapsedRealtime()
        fun attach(): Boolean = if (shellOwned) VScreenRemoteDisplay.setSurface(displayId, reader.surface).isSuccess
            else runCatching { virtualDisplay?.surface = reader.surface; true }.getOrDefault(false)
        var ok = attach()
        parked = false
        boost(3_000)
        val deadline = t0 + 3_000
        var halfRetried = false
        while (SystemClock.elapsedRealtime() < deadline && lastFrameAtMs < t0) {
            if (!halfRetried && SystemClock.elapsedRealtime() - t0 > 1_500) { halfRetried = true; ok = attach() || ok }
            Thread.sleep(50)
        }
        val woke = lastFrameAtMs >= t0
        return when {
            woke -> "副屏刚从省电暂停中恢复（${SystemClock.elapsedRealtime() - t0}ms），页面保留；应用可能刚回到前台，先 read 确认"
            ok -> "副屏已从省电暂停中恢复，但还没收到新画面（静止页面可能不重绘）；先 read 确认"
            else -> "副屏从省电暂停恢复失败：画面可能不更新。用 release 后重新 launch"
        }
    }

    /** Latest frame together with its version number (for OCR caching and stale checks). */
    fun latestWithVersion(): Pair<Bitmap, Long>? {
        val b = latest ?: return null
        return synchronized(frameLock) { (latestRaw ?: b) to _frameVersion.value }
    }

    /** Make the pump convert the very next frame regardless of the throttle. */
    fun requestFreshFrame() { lastConvertMs = 0L }

    /**
     * Wait until the screen stops changing: [stableSamples] consecutive
     * samples [sampleMs] apart with no visible change (structural signature),
     * after at least [minMs], or until [timeoutMs]. Returns (frame, waitedMs,
     * stable). A static screen produces no new frames, which counts as stable.
     */
    fun settle(minMs: Long = 300, timeoutMs: Long = 1_500, stableSamples: Int = 2, sampleMs: Long = 150): Triple<Bitmap?, Long, Boolean> {
        val start = SystemClock.elapsedRealtime()
        var prev: ScreenSig? = null
        var same = 0
        while (true) {
            requestFreshFrame()
            Thread.sleep(sampleMs)
            val now = SystemClock.elapsedRealtime()
            val sig = ScreenSig.of(latest)
            if (sig != null && prev != null && sig.compare(prev).kind == ScreenSig.Kind.NONE) same++ else same = 0
            prev = sig ?: prev
            if (same >= stableSamples && now - start >= minMs) return Triple(latest, now - start, true)
            if (now - start >= timeoutMs) return Triple(latest, now - start, false)
        }
    }

    data class FrameSample(
        val bitmap: Bitmap,
        val version: Long,
        val fresh: Boolean,
        val ageMs: Long,
        /** elapsed-realtime when this exact bitmap was published */
        val convertedAtMs: Long,
        /** elapsed-realtime when the producer delivered the source image */
        val receivedAtMs: Long,
    )

    /** A consistent bitmap/version/timestamp snapshot. */
    private fun publishedSample(now: Long, freshSinceMs: Long? = null): FrameSample? = synchronized(frameLock) {
        val b = latestRaw ?: return@synchronized null
        val converted = latestConvertedAtMs
        FrameSample(
            b, _frameVersion.value,
            freshSinceMs?.let { converted >= it } ?: false,
            (now - converted).coerceAtLeast(0L), converted, latestReceivedAtMs,
        )
    }

    /**
     * Request a frame and retain whether the result was actually fresh. A timeout may return the
     * last cached bitmap for read-only diagnostics, but callers must not present it as a new frame.
     */
    fun awaitFrameAfterInfo(notOlderThanMs: Long, timeoutMs: Long): FrameSample? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        requestFreshFrame()
        while (SystemClock.elapsedRealtime() < deadline) {
            val now = SystemClock.elapsedRealtime()
            val sample = publishedSample(now, notOlderThanMs)
            if (sample?.fresh == true) return sample
            Thread.sleep(30)
        }
        return publishedSample(SystemClock.elapsedRealtime(), notOlderThanMs)
    }

    /** Compatibility bitmap-only view. Prefer [awaitFrameAfterInfo] when freshness matters. */
    fun awaitFrameAfter(notOlderThanMs: Long, timeoutMs: Long): Bitmap? =
        awaitFrameAfterInfo(notOlderThanMs, timeoutMs)?.bitmap

    fun release() {
        if (released) return
        released = true
        listener?.let { l -> runCatching { (watchContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.unregisterDisplayListener(l) } }
        listener = null
        runCatching { reader.setOnImageAvailableListener(null, null) }
        val h = pumpHandler
        h?.removeCallbacks(flushPending)
        if (shellOwned) runCatching { VScreenRemoteDisplay.release(displayId) }
        else runCatching { virtualDisplay?.release() }
        // The reader is closed on the pump thread: convert() runs there and reads the image's
        // native buffer. Closing it from this thread while a frame was being copied crashed the
        // whole process (seen right after a rebuild), which took the screen and the chat down.
        val queued = h != null && runCatching {
            h.post {
                runCatching { pending?.close() }; pending = null
                runCatching { reader.close() }
            }
        }.getOrDefault(false)
        if (!queued) { runCatching { pending?.close() }; pending = null; runCatching { reader.close() } }
        runCatching { thread.quitSafely() }
        latestRaw = null
    }

    companion object {
        private const val TAG = "VScreenDisplay"
        const val DEFAULT_FRAME_INTERVAL_MS = 120L
        private var generationCounter = 0L

        /** Why the last shell-display attempt failed (null = it worked). Shown in launch errors. */
        @Volatile var lastRemoteError: String? = null
            private set

        /**
         * Take over a display the Shizuku service still holds from a previous
         * Minis process: give it a fresh ImageReader surface. Null on failure.
         */
        internal fun adopt(e: VScreenRemoteDisplay.Existing): VScreenDisplay? {
            val thread = HandlerThread("minis-vscreen-frames").apply { start() }
            val reader = ImageReader.newInstance(e.width, e.height, PixelFormat.RGBA_8888, 3)
            val r = VScreenRemoteDisplay.setSurface(e.displayId, reader.surface)
            if (r.isFailure) {
                AppLogger.warning(TAG, "adopt ${e.displayId} failed: ${r.exceptionOrNull()?.message}")
                runCatching { reader.close() }; thread.quitSafely()
                return null
            }
            val gen = synchronized(this) { ++generationCounter }
            val d = VScreenDisplay(e.displayId, e.width, e.height, e.dpi, gen, true, null, reader, thread,
                shellOwned = true, trusted = VScreenDisplayService.isTrusted(e.flags), flags = e.flags)
            d.startPump()
            AppLogger.info(TAG, "adopted shell display ${e.displayId} gen=$gen")
            return d
        }

        /**
         * Create the display. Returns the display or an error string, never
         * throws. Runs on any thread.
         */
        fun create(context: Context, name: String, width: Int, height: Int, dpi: Int): Result<VScreenDisplay> {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: return Result.failure(IllegalStateException("DisplayManager unavailable"))
            val thread = HandlerThread("minis-vscreen-frames").apply { start() }
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            // Preferred: a shell-owned (and on Android 13+ trusted) display made by
            // the Shizuku UserService. An app-owned display is untrusted: the system
            // refuses `am start --display` from the shell uid and silently opens the
            // app on the main screen instead.
            val remote = VScreenRemoteDisplay.create(name, width, height, dpi, reader.surface)
            remote.onSuccess { r ->
                lastRemoteError = null
                val gen = synchronized(this) { ++generationCounter }
                val d = VScreenDisplay(r.displayId, width, height, dpi, gen, true, null, reader, thread,
                    shellOwned = true, trusted = r.trusted, flags = r.flags)
                d.startPump()
                AppLogger.info(TAG, "created shell display ${d.displayId} ${width}x$height@$dpi flags=${r.flags} trusted=${r.trusted} gen=$gen")
                return Result.success(d)
            }
            lastRemoteError = remote.exceptionOrNull()?.message ?: "unknown"
            AppLogger.warning(TAG, "shell display unavailable ($lastRemoteError); falling back to an app-owned display")
            fun attempt(flags: Int): VirtualDisplay? = runCatching {
                dm.createVirtualDisplay(name, width, height, dpi, reader.surface, flags, null, Handler(thread.looper))
            }.onFailure {
                AppLogger.warning(TAG, "createVirtualDisplay(flags=$flags) failed: ${it.message}")
            }.getOrNull()

            val publicFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            var isPublic = true
            val vd = attempt(publicFlags)
                ?: attempt(DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY).also { isPublic = false }
            if (vd == null) {
                runCatching { reader.close() }
                thread.quitSafely()
                return Result.failure(
                    IllegalStateException("系统拒绝创建虚拟显示屏（ROM 策略或显示屏数量上限）"),
                )
            }
            val gen = synchronized(this) { ++generationCounter }
            val d = VScreenDisplay(vd.display.displayId, width, height, dpi, gen, isPublic, vd, reader, thread)
            d.startPump()
            AppLogger.info(TAG, "created display ${d.displayId} ${width}x$height@$dpi public=$isPublic gen=$gen")
            return Result.success(d)
        }
    }
}

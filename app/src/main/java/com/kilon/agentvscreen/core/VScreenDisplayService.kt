package com.kilon.agentvscreen.core

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import com.kilon.agentvscreen.host.AppLogger
import android.view.Surface

/**
 * [vscreen] Shizuku UserService: runs as the Shizuku shell user (uid 2000),
 * started by `Shizuku.bindUserService` from [VScreenRemoteDisplay].
 *
 * Why: a virtual display created by the app itself is an untrusted display
 * owned by the app's uid. Android only lets the display owner (or system)
 * put activities on such a display, so `am start --display N` run as the
 * shell user is refused and the system quietly opens the app on the main
 * screen. A display created here is owned by the shell uid (the same uid
 * that runs `am start` / `input`) and, on Android 13+, can be TRUSTED,
 * which is what scrcpy's `--new-display` does.
 *
 * The app keeps its ImageReader: its Surface travels here through binder,
 * so the frames still land in the app for the preview and screenshots.
 *
 * Raw binder protocol (no AIDL), interface [DESCRIPTOR]:
 *   CREATE   in: name, width, height, dpi, flags, Surface  out: status, displayId | message
 *   RELEASE  in: displayId                                 out: status
 *   PING     out: status, uid
 *   INJECT_KEY   in: displayId, keyCode, longPress(0/1)            out: status, injected(0/1)
 *   INJECT_DRAG  in: displayId, x1, y1, x2, y2, durationMs, holdMs  out: status, injected(0/1)
 *                (x1,y1)==(x2,y2) is a tap / long press of holdMs)
 *   REMOVE_TASK  in: taskId, expectedDisplayId                    out: status, removed(0/1)
 *                (v27: refused unless the system says the task is on expectedDisplayId)
 *   LIST_TASKS   in: displayId                                    out: status, n, n × (taskId, component, visible, activityType, rootTop)
 *   FOCUSED      in: -                                            out: status, displayId, taskId (-1 = unknown)
 *   FOCUS_TASK   in: taskId                                       out: status, ok(0/1)
 *   MOVE_TASK    in: taskId, displayId (v22 接力)                   out: status, moved(0/1), detail
 *   LIST         out: status, n, n x (displayId, w, h, dpi, flags)
 *   SET_SURFACE  in: displayId, Surface                            out: status
 *   PARK         in: displayId (surface -> null: display off, v21)  out: status
 *   TOUCH        in: displayId                                     out: status
 *   ROTATION     in: displayId                         out: status, rotation, logicalW, logicalH
 *   POINTER      in: displayId, op(0 down,1 move,2 up,3 up-all), pointerId, x, y
 *                out: status, injected(0/1), activePointers
 *                (fingers stay down between calls: joystick, hold-and-look, two-finger play)
 *   APS          in: op, pkg, ...   Honor/Huawei per-app resolution (aps_service, IHwApsManager)
 *                op 0 GET     out: status, available, [resolution, dynamicRatio, hasEntry, entryBytes] | reason
 *                op 1 SET     in: ratio                      out: status, returnCode, readBack
 *                op 2 RESTORE in: hadEntry, entryBytes, ratio out: status, readBack, hasEntryNow, detail
 *
 * The service runs as a Shizuku daemon: when the Minis process dies (killed
 * in the background, or restarted) its displays survive with the apps on
 * them, and the next Minis process adopts them (LIST + SET_SURFACE with a
 * fresh ImageReader). Displays nobody touched for [ORPHAN_MS] are released.
 * Input is injected with the target display id set on every event, from
 * the shell uid (INJECT_EVENTS), the way scrcpy does it.
 * status 0 = ok, 1 = error (followed by a message string).
 * Kept by proguard (class is instantiated by name in another process).
 */
class VScreenDisplayService : Binder {
    private val base: Context?
    private val displays = HashMap<Int, VirtualDisplay>()
    private val meta = HashMap<Int, IntArray>()
    private val touched = HashMap<Int, Long>()
    @Volatile private var reaperStarted = false

    private fun startReaper() {
        if (reaperStarted) return
        reaperStarted = true
        Thread {
            while (true) {
                Thread.sleep(60_000)
                val now = SystemClock.elapsedRealtime()
                val stale = synchronized(displays) { touched.filter { now - it.value > ORPHAN_MS }.keys.toList() }
                for (id in stale) {
                    synchronized(displays) { touched.remove(id); meta.remove(id); displays.remove(id) }?.let { runCatching { it.release() } }
                }
            }
        }.apply { isDaemon = true; name = "vscreen-reaper" }.start()
    }

    constructor() : super() { base = null }
    constructor(context: Context) : super() { base = context }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == DESTROY_CODE) {
            synchronized(displays) { displays.values.forEach { runCatching { it.release() } }; displays.clear() }
            System.exit(0)
            return true
        }
        if (code !in CREATE..FOCUS_TASK) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        val out = reply ?: return true
        try {
            when (code) {
                CREATE -> {
                    val name = data.readString() ?: "minis-vscreen"
                    val w = data.readInt(); val h = data.readInt(); val dpi = data.readInt()
                    val requested = data.readInt()
                    val surface = Surface.CREATOR.createFromParcel(data)
                    val vd = create(name, w, h, dpi, requested, surface)
                    val id = vd.display.displayId
                    synchronized(displays) {
                        displays[id] = vd
                        meta[id] = intArrayOf(w, h, dpi, lastFlags)
                        touched[id] = SystemClock.elapsedRealtime()
                    }
                    startReaper()
                    out.writeInt(0); out.writeInt(id); out.writeInt(lastFlags)
                }
                RELEASE -> {
                    val id = data.readInt()
                    synchronized(displays) { meta.remove(id); touched.remove(id); displays.remove(id) }?.let { runCatching { it.release() } }
                    out.writeInt(0)
                }
                PING -> { out.writeInt(0); out.writeInt(android.os.Process.myUid()) }
                INJECT_KEY -> {
                    val id = data.readInt(); val key = data.readInt(); val longPress = data.readInt() != 0
                    out.writeInt(0); out.writeInt(if (injectKey(id, key, longPress)) 1 else 0)
                }
                INJECT_DRAG -> {
                    val id = data.readInt()
                    val x1 = data.readInt(); val y1 = data.readInt(); val x2 = data.readInt(); val y2 = data.readInt()
                    val dur = data.readInt(); val hold = data.readInt()
                    out.writeInt(0); out.writeInt(if (injectDrag(id, x1, y1, x2, y2, dur, hold)) 1 else 0)
                }
                LIST -> {
                    val snap = synchronized(displays) { meta.map { it.key to it.value.copyOf() } }
                    out.writeInt(0); out.writeInt(snap.size)
                    for ((id, m) in snap) { out.writeInt(id); m.forEach { out.writeInt(it) } }
                }
                SET_SURFACE -> {
                    val id = data.readInt()
                    val surface = Surface.CREATOR.createFromParcel(data)
                    val vd = synchronized(displays) { touched[id] = SystemClock.elapsedRealtime(); displays[id] }
                        ?: throw IllegalStateException("display $id not found")
                    vd.surface = surface
                    out.writeInt(0)
                }
                TOUCH -> {
                    val id = data.readInt()
                    synchronized(displays) { if (displays.containsKey(id)) touched[id] = SystemClock.elapsedRealtime() }
                    out.writeInt(0)
                }
                USER_ACTIVITY -> {
                    val id = data.readInt()
                    val ok = userActivity(id)
                    out.writeInt(0); out.writeInt(if (ok) 1 else 0)
                }
                WAKE_UP_WITH_DISPLAY -> {
                    val id = data.readInt()
                    val ok = wakeUpWithDisplayId(id)
                    out.writeInt(0); out.writeInt(if (ok) 1 else 0)
                }
                PARK -> {
                    // v21 idle power saving: no surface = display off, the apps on it stop (pages kept).
                    val id = data.readInt()
                    val vd = synchronized(displays) { touched[id] = SystemClock.elapsedRealtime(); displays[id] }
                        ?: throw IllegalStateException("display $id not found")
                    vd.surface = null
                    out.writeInt(0)
                }
                ROTATION -> {
                    val id = data.readInt()
                    val disp = displayManager().getDisplay(id) ?: throw IllegalStateException("display $id not found")
                    val pt = android.graphics.Point()
                    @Suppress("DEPRECATION") disp.getRealSize(pt)
                    out.writeInt(0); out.writeInt(disp.rotation); out.writeInt(pt.x); out.writeInt(pt.y)
                }
                POINTER -> {
                    val id = data.readInt(); val op = data.readInt(); val pid = data.readInt()
                    val x = data.readFloat(); val y = data.readFloat()
                    synchronized(displays) { if (displays.containsKey(id)) touched[id] = SystemClock.elapsedRealtime() }
                    val okp = pointer(id, op, pid, x, y)
                    out.writeInt(0); out.writeInt(if (okp) 1 else 0); out.writeInt(synchronized(fingers) { fingers[id]?.list?.size ?: 0 })
                }
                REMOVE_TASK -> {
                    val taskId = data.readInt(); val expected = data.readInt()
                    // Never remove a task the system places on another display (the user's main screen).
                    val on = runCatching { rootTasks().firstOrNull { taskId in it.children.map { c -> c.first } }?.displayId }.getOrNull()
                    val okRemove = on != null && on == expected && removeTask(taskId)
                    out.writeInt(0); out.writeInt(if (okRemove) 1 else 0)
                }
                LIST_TASKS -> {
                    val id = data.readInt()
                    val all = rootTasks()
                    if (all.isNotEmpty() && all.all { it.displayId < 0 }) error("系统任务列表里没有 displayId")
                    val roots = all.filter { it.displayId == id }
                    val rows = roots.flatMap { r -> r.children.mapIndexed { i, c -> listOf(c.first, c.second, if (r.visible) 1 else 0, r.type, if (i == 0) r.top else null) } }
                    out.writeInt(0); out.writeInt(rows.size)
                    rows.forEach { row ->
                        out.writeInt(row[0] as Int); out.writeString(row[1] as String?); out.writeInt(row[2] as Int)
                        out.writeInt(row[3] as Int); out.writeString(row[4] as String?)
                    }
                }
                FOCUSED -> {
                    val f = runCatching { focusedRoot() }.getOrNull()
                    out.writeInt(0); out.writeInt(f?.first ?: -1); out.writeInt(f?.second ?: -1)
                }
                FOCUS_TASK -> {
                    val taskId = data.readInt()
                    val r = runCatching {
                        val a = atmService()
                        a.javaClass.getMethod("setFocusedTask", Int::class.javaPrimitiveType).invoke(a, taskId); true
                    }.getOrDefault(false)
                    out.writeInt(0); out.writeInt(if (r) 1 else 0)
                }
                MOVE_TASK -> {
                    val taskId = data.readInt(); val target = data.readInt()
                    val r = runCatching { moveTask(taskId, target) }
                    val e = r.exceptionOrNull()?.let { (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it }
                    out.writeInt(0); out.writeInt(if (r.isSuccess) 1 else 0)
                    out.writeString(e?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "")
                }
                APS -> {
                    val op = data.readInt()
                    val pkg = data.readString() ?: throw IllegalArgumentException("缺少包名")
                    when (op) {
                        0 -> {
                            val s = runCatching { aps() }
                            if (s.isFailure) { out.writeInt(0); out.writeInt(0); out.writeString(s.exceptionOrNull()?.message ?: "no aps_service") }
                            else {
                                val res = apsResolution(pkg)
                                val dyn = runCatching { (apsCall("getDynamicResolutionRatio", pkg) as Number).toFloat() }.getOrDefault(1f)
                                val info = runCatching { apsCall("getPackageApsInfo", pkg) }.getOrNull()
                                out.writeInt(0); out.writeInt(1); out.writeFloat(res); out.writeFloat(dyn)
                                out.writeInt(if (info != null) 1 else 0)
                                out.writeByteArray((info as? android.os.Parcelable)?.let { marshal(it) })
                            }
                        }
                        1 -> {
                            val ratio = data.readFloat()
                            val rc = apsCall("setResolution", pkg, ratio, false)
                            val code = if (rc is Number) rc.toInt() else if (rc == true) 0 else -1
                            out.writeInt(0); out.writeInt(code)
                            out.writeFloat(apsResolution(pkg))
                        }
                        2 -> {
                            val had = data.readInt() != 0
                            val blob = data.createByteArray()
                            val orig = data.readFloat()
                            val entry = if (had && blob != null) runCatching { unmarshal(blob) }.getOrNull() else null
                            val steps = ArrayList<String>()
                            fun hasNow() = runCatching { apsCall("getPackageApsInfo", pkg) != null }.getOrDefault(false)
                            when {
                                entry != null -> steps += "setPackageApsInfo=" + runCatching { apsCall("setPackageApsInfo", pkg, entry) }.fold({ "$it" }, { it.javaClass.simpleName })
                                had -> steps += "原配置无法还原（快照不可读），未动它"
                                hasNow() -> steps += "deletePackageApsInfo=" + runCatching { apsCall("deletePackageApsInfo", pkg) }.fold({ "$it" }, { it.javaClass.simpleName })
                                else -> steps += "无单独配置，无需删除"
                            }
                            var rb = apsResolution(pkg)
                            // Last resort only when it cannot create an entry that was not there before.
                            if (kotlin.math.abs(rb - orig) > 0.002f && (had || hasNow())) {
                                steps += "setResolution=" + runCatching { apsCall("setResolution", pkg, orig, true) }.fold({ "$it" }, { it.javaClass.simpleName })
                                rb = apsResolution(pkg)
                            }
                            out.writeInt(0); out.writeFloat(rb); out.writeInt(if (hasNow()) 1 else 0); out.writeString(steps.joinToString("，"))
                        }
                        else -> throw IllegalArgumentException("unknown aps op $op")
                    }
                }
            }
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            out.writeInt(1); out.writeString("${cause.javaClass.simpleName}: ${cause.message}")
        }
        return true
    }

    @Volatile private var lastFlags = 0

    // ── Honor/Huawei APS (per-app render resolution) ─────────────────────

    private var apsSvc: Any? = null

    /** android.aps.IHwApsManager proxy for the "aps_service" binder (not "aps"). */
    private fun aps(): Any = synchronized(this) {
        apsSvc?.let { return it }
        val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "aps_service") as? android.os.IBinder ?: throw IllegalStateException("本机没有 aps_service")
        val s = Class.forName("android.aps.IHwApsManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, b)
            ?: throw IllegalStateException("aps_service 接口不可用")
        apsSvc = s
        s
    }

    private fun apsCall(name: String, vararg a: Any?): Any? {
        val s = aps()
        val m = s.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == a.size }
            ?: throw NoSuchMethodException("IHwApsManager.$name/${a.size}")
        return m.invoke(s, *a)
    }

    private fun apsResolution(pkg: String): Float = (apsCall("getResolution", pkg) as Number).toFloat()

    private fun marshal(p: android.os.Parcelable): ByteArray? = runCatching {
        val pc = Parcel.obtain()
        try { pc.writeParcelable(p, 0); pc.marshall() } finally { pc.recycle() }
    }.getOrNull()

    private fun unmarshal(b: ByteArray): Any? {
        val pc = Parcel.obtain()
        try {
            pc.unmarshall(b, 0, b.size); pc.setDataPosition(0)
            @Suppress("DEPRECATION")
            return pc.readParcelable<android.os.Parcelable>(Class.forName("android.aps.IHwApsManager").classLoader)
        } finally { pc.recycle() }
    }

    // ── input injection ───────────────────────────────────────────────────

    /** (InputManager[Global] instance, injectInputEvent(InputEvent, int)). */
    private val injector: Pair<Any, java.lang.reflect.Method> by lazy {
        val im = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
        }.getOrNull() ?: android.hardware.input.InputManager::class.java.getMethod("getInstance").invoke(null)
        val m = im!!.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        im to m
    }
    private val setDisplayId: java.lang.reflect.Method by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    private fun inject(event: InputEvent, displayId: Int, mode: Int = 0): Boolean {
        setDisplayId.invoke(event, displayId)
        // 0 = INJECT_INPUT_EVENT_MODE_ASYNC: never block on a busy app.
        // 1 = WAIT_FOR_RESULT: used for the final UP so it is known to be queued.
        return injector.second.invoke(injector.first, event, mode) as Boolean
    }

    private fun injectKey(displayId: Int, code: Int, longPress: Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            0, InputDevice.SOURCE_KEYBOARD)
        var ok = inject(down, displayId)
        if (longPress) {
            Thread.sleep(600)
            val t = SystemClock.uptimeMillis()
            ok = inject(KeyEvent(now, t, KeyEvent.ACTION_DOWN, code, 1, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_LONG_PRESS, InputDevice.SOURCE_KEYBOARD), displayId) && ok
        }
        val up = KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            0, InputDevice.SOURCE_KEYBOARD)
        return inject(up, displayId) && ok
    }

    private fun motion(downTime: Long, action: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f,
            0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    /**
     * A finger drag from (x1,y1) to (x2,y2) over [durationMs], then held
     * still for [holdMs] before lifting. The hold brings the velocity to
     * zero, so lists move by exactly the dragged distance instead of
     * flinging on. Same start and end = tap (or long press with a long hold).
     */
    private fun injectDrag(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int): Boolean {
        val downTime = SystemClock.uptimeMillis()
        var ok = true
        fun send(action: Int, x: Float, y: Float) {
            val e = motion(downTime, action, x, y)
            ok = inject(e, displayId) && ok
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat())
        var lastX = x1.toFloat(); var lastY = y1.toFloat()
        try {
        if (x1 != x2 || y1 != y2) {
            val steps = (durationMs / 12).coerceIn(4, 400)
            for (i in 1..steps) {
                Thread.sleep((durationMs / steps).toLong().coerceAtLeast(1))
                // Ease in-out so the start does not look like a flick.
                val t = i.toFloat() / steps
                val e = if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2) * (-2 * t + 2) / 2
                lastX = x1 + (x2 - x1) * e; lastY = y1 + (y2 - y1) * e
                send(MotionEvent.ACTION_MOVE, lastX, lastY)
            }
        }
        var held = 0
        while (held < holdMs) {
            val step = minOf(16, holdMs - held)
            Thread.sleep(step.toLong()); held += step
            if (x1 != x2 || y1 != y2) send(MotionEvent.ACTION_MOVE, x2.toFloat(), y2.toFloat())
        }
        lastX = x2.toFloat(); lastY = y2.toFloat()
        } finally {
            // The finger must ALWAYS lift: a lost UP leaves the app mid-gesture
            // (a half-pulled drawer, a list stuck in overscroll) until the next touch.
            val up = motion(downTime, MotionEvent.ACTION_UP, lastX, lastY)
            val sent = runCatching { inject(up, displayId, 1) }.getOrDefault(false) ||
                runCatching { inject(up, displayId, 0) }.getOrDefault(false)
            up.recycle()
            ok = sent && ok
        }
        return ok
    }

    // ── held fingers (multi-touch across calls) ──────────────────────────────

    private class Finger(val id: Int, var x: Float, var y: Float)
    private class Gesture(val downTime: Long) { val list = ArrayList<Finger>(); var lastMs = SystemClock.uptimeMillis() }
    /** displayId -> fingers currently down, in down order. */
    private val fingers = HashMap<Int, Gesture>()
    @Volatile private var fingerWatch = false

    private fun multi(g: Gesture, action: Int): MotionEvent {
        val n = g.list.size
        val props = Array(n) { i -> MotionEvent.PointerProperties().apply { id = g.list[i].id; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(n) { i -> MotionEvent.PointerCoords().apply { x = g.list[i].x; y = g.list[i].y; pressure = 1f; size = 1f } }
        return MotionEvent.obtain(g.downTime, SystemClock.uptimeMillis(), action, n, props, coords, 0, 0, 1f, 1f,
            0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    /** One finger change on [displayId]; every event carries all fingers that are down. */
    private fun pointer(displayId: Int, op: Int, pid: Int, x: Float, y: Float): Boolean = synchronized(fingers) {
        fun send(g: Gesture, action: Int, mode: Int = 0): Boolean {
            val e = multi(g, action); g.lastMs = SystemClock.uptimeMillis()
            return try { inject(e, displayId, mode) } finally { e.recycle() }
        }
        when (op) {
            0 -> {
                val g = fingers[displayId]
                if (g == null) {
                    val ng = Gesture(SystemClock.uptimeMillis()).also { it.list += Finger(pid, x, y) }
                    fingers[displayId] = ng
                    startFingerWatch()
                    send(ng, MotionEvent.ACTION_DOWN)
                } else {
                    g.list.firstOrNull { it.id == pid }?.let { it.x = x; it.y = y; return@synchronized send(g, MotionEvent.ACTION_MOVE) }
                    g.list += Finger(pid, x, y)
                    val idx = g.list.size - 1
                    send(g, MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                }
            }
            1 -> {
                val g = fingers[displayId] ?: return@synchronized false
                val f = g.list.firstOrNull { it.id == pid } ?: return@synchronized false
                f.x = x; f.y = y
                send(g, MotionEvent.ACTION_MOVE)
            }
            2 -> {
                val g = fingers[displayId] ?: return@synchronized false
                val idx = g.list.indexOfFirst { it.id == pid }
                if (idx < 0) return@synchronized false
                if (x >= 0 && y >= 0) { g.list[idx].x = x; g.list[idx].y = y }
                val r = if (g.list.size == 1) {
                    send(g, MotionEvent.ACTION_UP, 1).also { fingers.remove(displayId) }
                } else {
                    send(g, MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)).also { g.list.removeAt(idx) }
                }
                r
            }
            else -> liftAll(displayId)
        }
    }

    /** Lift every finger on [displayId] (op 3, or the safety timeout). Caller holds [fingers]. */
    private fun liftAll(displayId: Int): Boolean {
        val g = fingers[displayId] ?: return true
        var ok = true
        while (g.list.size > 1) {
            val idx = g.list.size - 1
            val e = multi(g, MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            ok = runCatching { inject(e, displayId) }.getOrDefault(false) && ok; e.recycle()
            g.list.removeAt(idx)
        }
        if (g.list.isNotEmpty()) {
            val e = multi(g, MotionEvent.ACTION_UP)
            ok = runCatching { inject(e, displayId, 1) }.getOrDefault(false) && ok; e.recycle()
        }
        fingers.remove(displayId)
        return ok
    }

    /** A finger nobody moved for [FINGER_MAX_IDLE_MS] is lifted, so a dead Minis never leaves a finger down. */
    private fun startFingerWatch() {
        if (fingerWatch) return
        fingerWatch = true
        Thread {
            while (true) {
                runCatching { Thread.sleep(1_000) }
                synchronized(fingers) {
                    val now = SystemClock.uptimeMillis()
                    fingers.entries.filter { now - it.value.lastMs > FINGER_MAX_IDLE_MS }.map { it.key }.forEach { liftAll(it) }
                }
            }
        }.apply { isDaemon = true; name = "vscreen-fingers" }.start()
    }

    private fun atmService(): Any = runCatching {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
    }.getOrNull() ?: Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!

    /** One root task as the system reports it: its display, child tasks (id, component), visibility, activity type, top activity. */
    private class Root(val displayId: Int, val children: List<Pair<Int, String?>>, val visible: Boolean, val type: Int, val top: String?)

    private fun pub(o: Any, name: String): Any? = runCatching { o.javaClass.getField(name).get(o) }.getOrNull()

    private fun rootOf(o: Any): Root {
        val disp = (pub(o, "displayId") as? Int) ?: -1
        val ids = (pub(o, "childTaskIds") ?: pub(o, "taskIds")) as? IntArray ?: IntArray(0)
        @Suppress("UNCHECKED_CAST")
        val names = (pub(o, "childTaskNames") ?: pub(o, "taskNames")) as? Array<String?> ?: emptyArray()
        val ownId = pub(o, "taskId") as? Int
        val kids = if (ids.isNotEmpty()) ids.mapIndexed { i, t -> t to names.getOrNull(i) }
            else listOfNotNull(ownId?.let { it to (pub(o, "baseActivity") as? android.content.ComponentName)?.flattenToShortString() })
        val type = runCatching { o.javaClass.getMethod("getActivityType").invoke(o) as Int }.getOrElse {
            runCatching {
                val cfg = pub(o, "configuration") as android.content.res.Configuration
                val wc = cfg.javaClass.getField("windowConfiguration").get(cfg)
                wc.javaClass.getMethod("getActivityType").invoke(wc) as Int
            }.getOrDefault(0)
        }
        val top = (pub(o, "topActivity") as? android.content.ComponentName)?.flattenToShortString()
        return Root(disp, kids, (pub(o, "visible") as? Boolean) ?: false, type, top)
    }

    /** Every root task on every display, top first per display (ActivityTaskManager, display-scoped by the system itself). */
    private fun rootTasks(): List<Root> {
        val a = atmService()
        val list = runCatching { a.javaClass.getMethod("getAllRootTaskInfos").invoke(a) }
            .getOrElse { a.javaClass.getMethod("getAllStackInfos").invoke(a) } as List<*>
        return list.filterNotNull().map { rootOf(it) }
    }

    /** (displayId, taskId) of the root task that has the system's input focus. */
    private fun focusedRoot(): Pair<Int, Int>? {
        val a = atmService()
        val o = runCatching { a.javaClass.getMethod("getFocusedRootTaskInfo").invoke(a) }
            .getOrElse { a.javaClass.getMethod("getFocusedStackInfo").invoke(a) } ?: return null
        val r = rootOf(o)
        return r.displayId to (r.children.firstOrNull()?.first ?: -1)
    }

    /** Remove one task (the background screen's own copy of an app) without killing the app. */
    private fun removeTask(taskId: Int): Boolean {
        val atm = runCatching {
            Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        }.getOrNull() ?: Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val m = atm!!.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType)
        return m.invoke(atm, taskId) as Boolean
    }

    /** v22: move a whole task (its root task) to another display, as it is. */
    private fun moveTask(taskId: Int, displayId: Int) {
        val atm = runCatching {
            Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        }.getOrNull() ?: Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val ip = Int::class.javaPrimitiveType
        val m = runCatching { atm!!.javaClass.getMethod("moveRootTaskToDisplay", ip, ip) }.getOrNull()
            ?: atm!!.javaClass.getMethod("moveStackToDisplay", ip, ip)
        // On some ROMs (vivo) an app task sits inside another root task: the system only knows the root's id.
        val root = runCatching { rootIdOf(taskId) }.getOrNull()
        val ids = listOfNotNull(root, taskId).distinct()
        var last: Throwable? = null
        for (id in ids) {
            try { m.invoke(atm, id, displayId); return } catch (t: Throwable) { last = t }
        }
        val e = (last as? java.lang.reflect.InvocationTargetException)?.targetException ?: last
        throw IllegalStateException("tried root ${ids.joinToString("/")}: ${e?.javaClass?.simpleName}: ${e?.message}")
    }

    /** The root task that holds [taskId] (its own id when it is a root). */
    private fun rootIdOf(taskId: Int): Int? {
        val a = atmService()
        val list = runCatching { a.javaClass.getMethod("getAllRootTaskInfos").invoke(a) }
            .getOrElse { a.javaClass.getMethod("getAllStackInfos").invoke(a) } as List<*>
        for (o in list.filterNotNull()) {
            val own = (pub(o, "taskId") ?: pub(o, "stackId")) as? Int
            val kids = (pub(o, "childTaskIds") ?: pub(o, "taskIds")) as? IntArray
            if (own == taskId || kids?.contains(taskId) == true) return own
        }
        return null
    }

    private fun create(name: String, w: Int, h: Int, dpi: Int, requested: Int, surface: Surface): VirtualDisplay {
        val dm = displayManager()
        val candidates = if (requested != 0) listOf(requested) else defaultFlagLadder()
        var last: Throwable? = null
        for (f in candidates) {
            try {
                val vd = dm.createVirtualDisplay(name, w, h, dpi, surface, f)
                    ?: throw IllegalStateException("createVirtualDisplay returned null")
                lastFlags = f
                return vd
            } catch (t: Throwable) { last = t }
        }
        throw last ?: IllegalStateException("no flags tried")
    }

    private fun displayManager(): DisplayManager {
        val ctx = ShellContext(base ?: systemContext())
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(ctx)
    }

    private fun systemContext(): Context {
        if (Looper.getMainLooper() == null) runCatching { Looper.prepareMainLooper() }
        val at = Class.forName("android.app.ActivityThread")
        val current = runCatching { at.getMethod("currentActivityThread").invoke(null) }.getOrNull()
            ?: at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(current) as Context
    }

    /** Presents itself as com.android.shell so DisplayManagerService's package/uid check passes. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PKG
        override fun getOpPackageName(): String = SHELL_PKG
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            if (Build.VERSION.SDK_INT >= 31) AttributionSource.Builder(android.os.Process.myUid()).setPackageName(SHELL_PKG).build()
            else super.getAttributionSource()
    }


    private data class OwnedDisplayInfo(val displayId: Int, val groupId: Int, val flags: Int)

    /** The service's own VD plus the system's real DisplayInfo; never infer ownership from a caller-supplied id. */
    private fun ownedDisplayInfo(displayId: Int): OwnedDisplayInfo? = runCatching {
        val vd = synchronized(displays) { displays[displayId] } ?: return null
        val display = displayManager().getDisplay(displayId) ?: return null
        if (vd.display.displayId != displayId || !display.isValid) return null
        val infoClass = Class.forName("android.view.DisplayInfo")
        val info = infoClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val getter = android.view.Display::class.java.getDeclaredMethod("getDisplayInfo", infoClass).apply { isAccessible = true }
        val returned = getter.invoke(display, info)
        if (returned is Boolean && !returned) return null
        val group = infoClass.getDeclaredField("displayGroupId").apply { isAccessible = true }.getInt(info)
        if (group <= 0) return null
        val flags = synchronized(displays) { meta[displayId]?.getOrNull(3) ?: 0 }
        OwnedDisplayInfo(displayId, group, flags)
    }.getOrNull()

    private fun powerService(): Any? = runCatching {
        val ctx = ShellContext(base ?: systemContext())
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return null
        val field = pm.javaClass.getDeclaredField("mService").apply { isAccessible = true }
        field.get(pm)
    }.getOrNull()

    /** Call only IPowerManager.userActivity(displayId,...), after proving this service owns a real non-default group. */
    private fun userActivity(displayId: Int): Boolean = runCatching {
        val owned = ownedDisplayInfo(displayId) ?: return false
        if (owned.flags and OWN_DISPLAY_GROUP == 0) return false
        val service = powerService() ?: return false
        val method = service.javaClass.methods.firstOrNull { m ->
            m.name == "userActivity" && m.parameterTypes.contentEquals(arrayOf(
                Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            ))
        } ?: return false
        method.invoke(service, displayId, android.os.SystemClock.uptimeMillis(), 2, 0)
        synchronized(displays) { touched[displayId] = android.os.SystemClock.elapsedRealtime() }
        true
    }.getOrDefault(false)

    /** Android 16+ precise wake path. Missing method/constant or DEVICE_POWER denial is a hard refusal. */
    private fun wakeUpWithDisplayId(displayId: Int): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 36) return false
        val owned = ownedDisplayInfo(displayId) ?: return false
        if (owned.flags and OWN_DISPLAY_GROUP == 0) return false
        val service = powerService() ?: return false
        val method = service.javaClass.methods.firstOrNull { m ->
            m.name == "wakeUpWithDisplayId" && m.parameterTypes.contentEquals(arrayOf(
                Long::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                String::class.java, String::class.java, Int::class.javaPrimitiveType!!,
            ))
        } ?: return false
        val reason = android.os.PowerManager::class.java.getDeclaredField("WAKE_REASON_APPLICATION")
            .apply { isAccessible = true }.getInt(null)
        method.invoke(service, android.os.SystemClock.uptimeMillis(), reason, "vscreen", SHELL_PKG, displayId)
        synchronized(displays) { touched[displayId] = android.os.SystemClock.elapsedRealtime() }
        true
    }.onFailure { t ->
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        AppLogger.warning("VScreenDisplayService", "wakeUpWithDisplayId refused: ${cause.javaClass.simpleName}: ${cause.message}")
    }.getOrDefault(false)

    companion object {
        const val DESCRIPTOR = "com.kilon.agentvscreen.core.VScreenDisplayService"
        const val CREATE = FIRST_CALL_TRANSACTION
        const val RELEASE = FIRST_CALL_TRANSACTION + 1
        const val PING = FIRST_CALL_TRANSACTION + 2
        const val INJECT_KEY = FIRST_CALL_TRANSACTION + 3
        const val INJECT_DRAG = FIRST_CALL_TRANSACTION + 4
        const val REMOVE_TASK = FIRST_CALL_TRANSACTION + 5
        const val LIST = FIRST_CALL_TRANSACTION + 6
        const val SET_SURFACE = FIRST_CALL_TRANSACTION + 7
        const val TOUCH = FIRST_CALL_TRANSACTION + 8
        const val USER_ACTIVITY = FIRST_CALL_TRANSACTION + 17
        const val WAKE_UP_WITH_DISPLAY = FIRST_CALL_TRANSACTION + 18
        const val ROTATION = FIRST_CALL_TRANSACTION + 9
        const val POINTER = FIRST_CALL_TRANSACTION + 10
        const val APS = FIRST_CALL_TRANSACTION + 11
        const val PARK = FIRST_CALL_TRANSACTION + 12
        const val MOVE_TASK = FIRST_CALL_TRANSACTION + 13
        const val LIST_TASKS = FIRST_CALL_TRANSACTION + 14
        const val FOCUSED = FIRST_CALL_TRANSACTION + 15
        const val FOCUS_TASK = FIRST_CALL_TRANSACTION + 16
        /** A held finger with no update for this long is lifted by the service. */
        const val FINGER_MAX_IDLE_MS = 30_000L
        /** A display nobody touched for this long (Minis gone) is released. */
        const val ORPHAN_MS = 20 * 60_000L
        /** Bump when the protocol changes, so Shizuku restarts an old service process. */
        const val PROTOCOL = 10
        /** Shizuku's reserved "destroy user service" transaction. */
        const val DESTROY_CODE = 16777115
        private const val SHELL_PKG = "com.android.shell"

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (some are @hide).
        private const val PUBLIC = 1 shl 0
        private const val OWN_CONTENT_ONLY = 1 shl 3
        private const val SUPPORTS_TOUCH = 1 shl 6
        private const val ROTATES_WITH_CONTENT = 1 shl 7
        private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val TRUSTED = 1 shl 10
        private const val OWN_DISPLAY_GROUP = 1 shl 11
        private const val ALWAYS_UNLOCKED = 1 shl 12
        private const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val OWN_FOCUS = 1 shl 14
        private const val DEVICE_DISPLAY_GROUP = 1 shl 15
        /** VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED (API 34): needs OWN_FOCUS + TRUSTED. */
        private const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        /** Richest first (scrcpy --new-display set), then progressively plainer for strict ROMs. */
        fun defaultFlagLadder(): List<Int> {
            val basic = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or DESTROY_CONTENT_ON_REMOVAL
            val out = ArrayList<Int>()
            if (Build.VERSION.SDK_INT >= 34) {
                // v27: first try a screen that never takes the main screen's focus (keyboard stays with the user).
                out += basic or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED or OWN_FOCUS or DEVICE_DISPLAY_GROUP or STEAL_TOP_FOCUS_DISABLED
                out += basic or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED or OWN_FOCUS or DEVICE_DISPLAY_GROUP
            }
            if (Build.VERSION.SDK_INT >= 33) {
                out += basic or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
                out += basic or TRUSTED
            }
            out += basic
            out += PUBLIC or OWN_CONTENT_ONLY
            return out
        }

        fun isTrusted(flags: Int) = flags and TRUSTED != 0
        fun keepsMainFocus(flags: Int) = flags and STEAL_TOP_FOCUS_DISABLED != 0

        /** Human-readable flag names for status output. */
        fun flagNames(flags: Int): List<String> = listOf(
            PUBLIC to "PUBLIC", OWN_CONTENT_ONLY to "OWN_CONTENT_ONLY", SUPPORTS_TOUCH to "SUPPORTS_TOUCH",
            ROTATES_WITH_CONTENT to "ROTATES_WITH_CONTENT", DESTROY_CONTENT_ON_REMOVAL to "DESTROY_CONTENT_ON_REMOVAL",
            TRUSTED to "TRUSTED", OWN_DISPLAY_GROUP to "OWN_DISPLAY_GROUP", ALWAYS_UNLOCKED to "ALWAYS_UNLOCKED",
            TOUCH_FEEDBACK_DISABLED to "TOUCH_FEEDBACK_DISABLED", OWN_FOCUS to "OWN_FOCUS",
            DEVICE_DISPLAY_GROUP to "DEVICE_DISPLAY_GROUP", STEAL_TOP_FOCUS_DISABLED to "STEAL_TOP_FOCUS_DISABLED",
        ).filter { flags and it.first != 0 }.map { it.second }
    }
}


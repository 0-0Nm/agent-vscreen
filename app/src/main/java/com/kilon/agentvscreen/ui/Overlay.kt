package com.kilon.agentvscreen.ui

import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kilon.agentvscreen.api.Turns
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.host.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The floating window over every app (a system overlay), so the user sees the background screen whatever agent app is
 * in front. The picture fills a rounded card edge to edge, inside a breathing border whose colour says who has the
 * screen (rainbow: the AI acts; blue: it waits; green: you drive; red: you stopped it; grey: idle).
 *
 * Gestures (handled on raw screen coordinates by [GestureFrame], so moving the window never jitters):
 *  - one finger drags the window, and it keeps gliding when you let go of it moving (inertia);
 *  - fling it toward a corner: it shrinks to the smallest size and parks in that corner; fling a small one back toward the
 *    middle and it grows to its earlier size;
 *  - carry it to the left / right edge and it turns into a small tab right under the finger, while you still hold it;
 *    pull the tab inward and the window opens under the finger and keeps following (folding follows the hand);
 *  - let go (or glide) with a good part of it past any of the four edges: it folds into the tab on the nearer side
 *    (tap the tab to bring it back);
 *  - two fingers pinch to resize, anchored at the point between them (120 dp … 85 % of the screen);
 *  - hold one finger still for 1 s to take over (a ring fills); a light tap only shows these hints;
 *  - while you hold the screen, taps / swipes / long presses on the picture go to the background screen, and you move
 *    the window by its title pill.
 * Buttons: ■ stop the AI (its calls get USER_STOPPED until you hand back), × close the screen (tap twice), ⤢ full screen,
 * and the green 交还 AI pill.
 * While our own full-screen page is in front, the window steps aside (the page already shows the same picture).
 */
object Overlay {
    private const val PREFS = "agent_vscreen_overlay"
    private var ctx: Context? = null
    private var root: GestureFrame? = null
    /** The window manager the window lives in, and whether that is the accessibility service's (see [show]). */
    private var hostWm: WindowManager? = null
    @Volatile var hostedByA11y = false; private set
    private var owner: OverlayOwner? = null
    internal var lp: WindowManager.LayoutParams? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // UI state, main thread only.
    internal val widthPx = mutableFloatStateOf(0f)
    internal val docked = mutableStateOf(false)
    internal val dockRight = mutableStateOf(true)
    internal val hold = mutableFloatStateOf(0f)
    internal val holdAt = mutableStateOf(Offset.Zero)
    internal val hintAt = mutableLongStateOf(0L)
    internal val closeArmedAt = mutableLongStateOf(0L)
    internal val frameRect = RectF()
    internal val pillRect = RectF()
    internal val controls = HashMap<String, RectF>()

    /** Our full-screen page is in front: it shows the same picture, so the floating window steps aside. */
    private val fullscreenOpen = MutableStateFlow(false)
    fun fullscreenShown(open: Boolean) { fullscreenOpen.value = open }

    fun attach(c: Context) {
        ctx = c.applicationContext
        job?.cancel()
        job = scope.launch {
            combine(BackgroundOperation.state.map { it.displayId != null && it.showPreview }.distinctUntilChanged(), fullscreenOpen) { up, full -> up to full }
                .distinctUntilChanged().collect { (up, full) ->
                    AppLogger.info("Overlay", when { !up -> "no screen / preview off: hide"; full -> "full-screen page in front: hide"; else -> "screen up: show" })
                    if (up && !full) show() else hide()
                }
        }
    }

    fun detach() { job?.cancel(); job = null; hide() }

    /** Re-check after the overlay permission was granted. */
    fun refresh() { val s = BackgroundOperation.state.value; if (s.displayId != null && s.showPreview && !fullscreenOpen.value) show() }

    /** The accessibility service came or went: move the window to the better host. Main thread. */
    fun rehost() {
        android.os.Handler(Looper.getMainLooper()).post {
            val want = com.kilon.agentvscreen.host.VScreenAccessibilityService.getInstance() != null
            if (root != null && want != hostedByA11y) { AppLogger.info("Overlay", "rehost (a11y=$want)"); hide(); refresh() }
        }
    }

    private fun prefs() = ctx!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun metricsWm(): WindowManager? = hostWm ?: ctx?.getSystemService(WindowManager::class.java)
    internal fun screenW() = runCatching { metricsWm()!!.maximumWindowMetrics.bounds.width() }.getOrNull() ?: ctx?.resources?.displayMetrics?.widthPixels ?: 1080
    internal fun screenH() = runCatching { metricsWm()!!.maximumWindowMetrics.bounds.height() }.getOrNull() ?: ctx?.resources?.displayMetrics?.heightPixels ?: 2400
    internal fun dp(v: Float) = v * (ctx?.resources?.displayMetrics?.density ?: 3f)

    /** Aspect (w / h) of the background screen. */
    internal fun aspect(): Float {
        val s = BackgroundOperation.state.value
        val d = s.ownerSessionId?.let { BackgroundOperation.previewDisplayFor(it) }
        val w = d?.width ?: s.screenWidth; val h = d?.height ?: s.screenHeight
        return if (w > 0 && h > 0) w.toFloat() / h else 9f / 19.5f
    }

    internal fun clampWidth(w: Float): Float {
        val a = aspect()
        val max = minOf(screenW() * 0.85f, screenH() * 0.85f * a)
        return w.coerceIn(dp(120f).coerceAtMost(max), max)
    }

    /**
     * Where the window lives. Not a copy of Minis (whose preview sat inside its own chat activity): a system overlay of an
     * ordinary app (TYPE_APPLICATION_OVERLAY) is hidden by Android on EVERY display while any window that asks to hide
     * non-system overlays is showing — Settings, the package installer, permission dialogs, many bank apps do — and the
     * agent opens exactly those on the background screen. So the window is an accessibility overlay of our read-only
     * service when it is on (those are never hidden that way), and an app overlay only as the fallback.
     */
    private fun show() {
        val c = ctx ?: return
        if (root != null) return
        val svc = com.kilon.agentvscreen.host.VScreenAccessibilityService.getInstance()
        val a11y = svc != null
        if (!a11y && !Settings.canDrawOverlays(c)) { AppLogger.info("Overlay", "no overlay permission and no accessibility: window not shown"); return }
        val wm: WindowManager = if (svc != null) svc.getSystemService(WindowManager::class.java)
            else runCatching {
                val dm = c.getSystemService(android.hardware.display.DisplayManager::class.java)
                c.createWindowContext(dm.getDisplay(android.view.Display.DEFAULT_DISPLAY), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                    .getSystemService(WindowManager::class.java)
            }.getOrElse { c.getSystemService(WindowManager::class.java) }
        val o = OverlayOwner().also { owner = it }
        val p = prefs()
        val defW = if (aspect() > 1f) screenW() * 0.62f else screenW() * 0.34f
        widthPx.floatValue = clampWidth(p.getFloat("w", defW))
        docked.value = p.getBoolean("docked", false)
        dockRight.value = p.getBoolean("dock_right", true)
        val l = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (a11y) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = p.getInt("x", (screenW() - widthPx.floatValue - dp(16f)).toInt())
            y = p.getInt("y", (screenH() * 0.18f).toInt())
        }
        if (docked.value) l.x = if (dockRight.value) screenW() - dp(TAB_W).toInt() else 0
        lp = l
        val g = GestureFrame(c)
        val v = ComposeView(c).apply {
            setViewTreeLifecycleOwner(o); setViewTreeSavedStateRegistryOwner(o); setViewTreeViewModelStoreOwner(o)
            setContent { OverlayContent() }
        }
        g.setViewTreeLifecycleOwner(o); g.setViewTreeSavedStateRegistryOwner(o); g.setViewTreeViewModelStoreOwner(o)
        g.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        runCatching { wm.addView(g, l); root = g; hostWm = wm; hostedByA11y = a11y }
            .onSuccess { AppLogger.info("Overlay", "window added (${if (a11y) "accessibility overlay" else "app overlay"})") }
            .onFailure { AppLogger.warning("Overlay", "addView: ${it.message}"); o.destroy(); owner = null }
    }

    private fun hide() {
        stopAnim()
        val v = root ?: return
        runCatching { hostWm?.removeView(v) }.onFailure { AppLogger.warning("Overlay", "removeView: ${it.message}") }
        root = null; hostWm = null; owner?.destroy(); owner = null
        controls.clear()
    }

    internal fun place(x: Int, y: Int) {
        val v = root ?: return; val l = lp ?: return
        l.x = x; l.y = y.coerceIn(if (docked.value) 0 else -(winH() * 0.6f).toInt(), screenH() - dp(48f).toInt())
        runCatching { hostWm?.updateViewLayout(v, l) }
    }

    internal fun save() {
        val l = lp ?: return
        prefs().edit().putFloat("w", widthPx.floatValue).putInt("x", l.x).putInt("y", l.y)
            .putBoolean("docked", docked.value).putBoolean("dock_right", dockRight.value).apply()
    }

    // ---- Where the window comes to rest (release, fling, rotation). ----
    private const val FOLD = 0.2f      // this much of the window past an edge → fold into a tab
    private const val GLIDE_S = 0.2f   // inertia: where a fling would carry the window (velocity × this)
    private var anim: android.animation.ValueAnimator? = null

    internal fun winW(): Float = root?.width?.takeIf { it > 0 }?.toFloat() ?: (widthPx.floatValue + dp(12f))
    internal fun winH(): Float = root?.height?.takeIf { it > 0 }?.toFloat() ?: (widthPx.floatValue / aspect() + dp(12f))
    private fun topMargin() = dp(36f)
    /** The folded tab (see [DockTab]). */
    internal const val TAB_W = 26f
    internal const val TAB_H = 60f
    private fun bottomMargin() = dp(48f)
    internal fun stopAnim() { anim?.cancel(); anim = null }
    internal fun isMini() = widthPx.floatValue <= clampWidth(0f) + dp(2f)

    /** Glide to (x, y), optionally changing the width, then run [then] (default: remember the place). */
    private fun animateTo(x: Int, y: Int, width: Float? = null, ms: Long = 240L, then: (() -> Unit)? = null) {
        if (lp == null) return
        stopAnim()
        val l = lp!!
        val x0 = l.x; val y0 = l.y; val w0 = widthPx.floatValue
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
        a.duration = if (animateLook) ms else 0L
        a.interpolator = android.view.animation.DecelerateInterpolator(1.5f)
        a.addUpdateListener {
            val t = it.animatedValue as Float
            if (width != null) widthPx.floatValue = w0 + (width - w0) * t
            place((x0 + (x - x0) * t).roundToInt(), (y0 + (y - y0) * t).roundToInt())
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (anim === a) anim = null
                if (!cancelled) { if (then != null) then() else save() }
            }
        })
        anim = a; a.start()
    }

    /** After a plain release or a rotation: fold if a good part is past an edge, else keep it fully on screen. */
    internal fun settle(fingerY: Float? = null) { val l = lp ?: return; restAt(l.x.toFloat(), l.y.toFloat(), fingerY) }

    /**
     * The window was let go while moving (raw px/s). It glides on; flung toward a corner it shrinks to the smallest size
     * there; a small one flung back toward the middle grows to its earlier size; otherwise it rests like a release.
     */
    internal fun fling(vx: Float, vy: Float, fingerY: Float? = null) {
        val l = lp ?: return
        val sw = screenW().toFloat(); val sh = screenH().toFloat()
        val w = winW(); val h = winH()
        val px = l.x + vx * GLIDE_S; val py = l.y + vy * GLIDE_S
        val cx = px + w / 2; val cy = py + h / 2
        val ax = abs(vx); val ay = abs(vy)
        val diagonal = min(ax, ay) / max(ax, ay).coerceAtLeast(1f) > 0.35f
        val towardCornerX = (vx < 0 && cx < sw * 0.4f) || (vx > 0 && cx > sw * 0.6f)
        val towardCornerY = (vy < 0 && cy < sh * 0.4f) || (vy > 0 && cy > sh * 0.6f)
        when {
            diagonal && towardCornerX && towardCornerY -> toCorner(right = vx > 0, bottom = vy > 0)
            isMini() && prefs().contains("w_full") && cx in sw * 0.25f..sw * 0.75f && cy in sh * 0.2f..sh * 0.8f -> grow(px, py)
            else -> restAt(px, py, fingerY?.let { it + vy * GLIDE_S })
        }
    }

    private fun toCorner(right: Boolean, bottom: Boolean) {
        if (!isMini()) prefs().edit().putFloat("w_full", widthPx.floatValue).apply()
        val mw = clampWidth(0f)
        val k = (mw + dp(12f)) / winW()
        val nw = mw + dp(12f); val nh = winH() * k
        val m = dp(10f)
        val x = if (right) screenW() - nw - m else m
        val y = if (bottom) screenH() - nh - bottomMargin() else topMargin()
        AppLogger.info("Overlay", "fling to ${if (bottom) "bottom" else "top"}-${if (right) "right" else "left"} corner: smallest")
        animateTo(x.toInt(), y.toInt(), mw, 300L)
    }

    private fun grow(px: Float, py: Float) {
        val fw = clampWidth(prefs().getFloat("w_full", widthPx.floatValue))
        val k = (fw + dp(12f)) / winW()
        val nw = fw + dp(12f); val nh = winH() * k
        val sw = screenW().toFloat(); val sh = screenH().toFloat()
        val x = (px + winW() / 2 - nw / 2).coerceIn(0f, (sw - nw).coerceAtLeast(0f))
        val y = (py + winH() / 2 - nh / 2).coerceIn(topMargin(), (sh - nh - bottomMargin()).coerceAtLeast(topMargin()))
        animateTo(x.toInt(), y.toInt(), fw, 300L)
    }

    /**
     * Come to rest with the window's top-left at (px, py): fold past any edge, else glide fully onto the screen.
     * The tab lands where the user let go ([fingerY], centred on the finger), not at the window's middle — otherwise
     * it appears somewhere other than where it was put. Without a finger (rotation) it keeps the window's top.
     */
    private fun restAt(px: Float, py: Float, fingerY: Float? = null) {
        val sw = screenW().toFloat(); val sh = screenH().toFloat()
        val w = winW(); val h = winH()
        val side: Boolean? = when {
            px < -w * FOLD -> false
            px + w > sw + w * FOLD -> true
            py < -h * FOLD || py + h > sh + h * FOLD -> px + w / 2 > sw / 2
            else -> null
        }
        if (side != null) {
            val ty = (fingerY?.let { it - dp(TAB_H) / 2 } ?: py).coerceIn(topMargin(), sh - dp(TAB_H) - bottomMargin())
            // Shrink toward the spot where the tab will sit, then become the tab exactly there.
            val l = lp!!
            val tabCx = if (side) sw - dp(TAB_W) / 2 else dp(TAB_W) / 2
            val v = content()
            stopAnim()
            if (v == null || !animateLook) { dock(side, ty.toInt()); resetShrink(); return }
            v.animate().cancel()
            v.pivotX = (tabCx - l.x).coerceIn(0f, w); v.pivotY = (ty + dp(TAB_H) / 2 - l.y).coerceIn(0f, h)
            v.animate().scaleX(tabScale()).scaleY(tabScale()).alpha(0.5f).setDuration(170L)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
                .withEndAction { swapFade(); dock(side, ty.toInt()) }.start()
            return
        }
        val x = px.coerceIn(0f, (sw - w).coerceAtLeast(0f))
        val y = py.coerceIn(topMargin().coerceAtMost(sh - h), (sh - h - bottomMargin()).coerceAtLeast(0f))
        animateTo(x.toInt(), y.toInt())
    }

    internal fun dock(right: Boolean, y: Int? = null) {
        val l = lp ?: return
        stopAnim()
        docked.value = true; dockRight.value = right
        val ty = (y ?: l.y).coerceIn(topMargin().toInt(), (screenH() - dp(TAB_H) - bottomMargin()).toInt())
        place(if (right) screenW() - dp(TAB_W).toInt() else 0, ty)
        save()
    }

    // ---- The window <-> tab change, drawn as one continuous motion around the finger. ----
    private fun content(): android.view.View? = root?.getChildAt(0)
    private fun tabScale(): Float = (dp(TAB_W) * 1.6f / winW()).coerceIn(0.08f, 1f)

    /**
     * Near a side edge the window shrinks toward the finger ([p] 0 -> 1 as the finger nears the edge), so by the time it
     * becomes the tab it is already tab-sized right where the finger is: no jump from "big window" to "tab elsewhere".
     */
    internal fun shrinkLive(pivotX: Float, pivotY: Float, p: Float) {
        val v = content() ?: return
        v.animate().cancel()
        val sc = 1f - p * (1f - tabScale())
        v.pivotX = pivotX; v.pivotY = pivotY; v.scaleX = sc; v.scaleY = sc; v.alpha = 1f - 0.4f * p
    }

    internal fun resetShrink() { content()?.let { it.animate().cancel(); it.scaleX = 1f; it.scaleY = 1f; it.alpha = 1f } }

    /** The content is about to change between window and tab: hide the one stale frame, then fade the new one in. */
    private fun swapFade() {
        val v = content() ?: return
        v.animate().cancel(); v.alpha = 0f
        v.postOnAnimationDelayed({ v.scaleX = 1f; v.scaleY = 1f; v.animate().alpha(1f).setDuration(if (animateLook) 110L else 0L).start() }, 34L)
    }

    /** While a finger holds it: become (or stay) the tab on that side, centred on the finger, [give] px inward. */
    internal fun dockLive(right: Boolean, fingerY: Float, give: Float) {
        stopAnim()
        if (!docked.value || dockRight.value != right) { if (!docked.value) swapFade(); docked.value = true; dockRight.value = right }
        val ty = (fingerY - dp(TAB_H) / 2).coerceIn(topMargin(), screenH() - dp(TAB_H) - bottomMargin())
        place(if (right) (screenW() - dp(TAB_W) - give).toInt() else give.toInt(), ty.toInt())
    }

    /** While a finger holds the tab and pulls it inward: open the window under the finger, held near its edge side. */
    internal fun undockAt(fingerX: Float, fingerY: Float) {
        stopAnim()
        val right = dockRight.value
        docked.value = false
        val w = widthPx.floatValue + dp(12f)
        val h = w / aspect()
        place((fingerX - if (right) w * 0.8f else w * 0.2f).toInt(), (fingerY - h * 0.2f).toInt())
        // Grow out of the finger instead of appearing whole.
        content()?.let { v ->
            v.animate().cancel()
            v.pivotX = if (right) w * 0.8f else w * 0.2f; v.pivotY = h * 0.2f
            v.scaleX = tabScale(); v.scaleY = tabScale(); v.alpha = 0.6f
            v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(if (animateLook) 200L else 0L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        }
    }

    internal fun undock() {
        val l = lp ?: return
        docked.value = false
        val w = widthPx.floatValue + dp(12f)
        val h = w / aspect()
        val y = l.y.toFloat().coerceIn(topMargin(), (screenH() - h - bottomMargin()).coerceAtLeast(topMargin()))
        place(if (dockRight.value) (screenW() - w - dp(8f)).toInt() else dp(8f).toInt(), y.toInt())
        save()
    }

    internal fun openFull() {
        val c = ctx ?: return
        runCatching { c.startActivity(Intent(c, FullscreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    internal fun hitControl(x: Float, y: Float) = controls.values.any { it.contains(x, y) }

    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val reg = LifecycleRegistry(this)
        private val ssr = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()
        init { ssr.performRestore(null); reg.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = reg
        override val savedStateRegistry: SavedStateRegistry get() = ssr.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store
        fun destroy() { reg.currentState = Lifecycle.State.DESTROYED; store.clear() }
    }
}

/** Map a point inside the picture to background-screen pixels (null: outside the picture). */
internal fun pictureToScreen(lx: Float, ly: Float, frame: RectF): Pair<Int, Int>? {
    val s = BackgroundOperation.state.value
    val d = s.ownerSessionId?.let { BackgroundOperation.previewDisplayFor(it) } ?: return null
    if (frame.width() <= 0f || !frame.contains(lx, ly)) return null
    val x = ((lx - frame.left) / frame.width() * d.width).roundToInt().coerceIn(0, d.width - 1)
    val y = ((ly - frame.top) / frame.height() * d.height).roundToInt().coerceIn(0, d.height - 1)
    return x to y
}

/**
 * All window gestures, on raw coordinates. Events go to the Compose buttons only when the finger went down on one and
 * stays put; anything that becomes a drag / pinch / hold is ours (the buttons get a cancel).
 */
@SuppressLint("ViewConstructor")
private class GestureFrame(ctx: Context) : FrameLayout(ctx) {
    private enum class Claim { NONE, MOVE, PINCH, SWIPE, HELD }
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val main = Handler(Looper.getMainLooper())
    private var claim = Claim.NONE
    private var onControl = false
    private var onPill = false
    private var downRaw = Offset.Zero
    private var downLocal = Offset.Zero
    private var lastLocal = Offset.Zero
    private var downAt = 0L
    private var winX = 0; private var winY = 0
    private var pinchDist = 0f; private var pinchW = 0f; private var pinchMid = Offset.Zero
    private var childGotDown = false
    /** Recent raw positions while moving the window (time ms, x, y): the release speed for inertia. */
    private val trail = ArrayDeque<Triple<Long, Float, Float>>()
    private val flingMin = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity.toFloat().coerceAtLeast(Overlay.dp(450f))

    private fun releaseSpeed(): Pair<Float, Float>? {
        val now = SystemClock.uptimeMillis()
        while (trail.size > 2 && now - trail.first().first > 100L) trail.removeFirst()
        if (trail.size < 2) return null
        val a = trail.first(); val b = trail.last()
        val dt = (b.first - a.first).coerceAtLeast(8L) / 1000f
        return (b.second - a.second) / dt to (b.third - a.third) / dt
    }

    private val holdTick = object : Runnable {
        override fun run() {
            if (claim != Claim.NONE) return
            val p = ((SystemClock.uptimeMillis() - downAt - 150L) / 850f).coerceIn(0f, 1f)
            Overlay.hold.floatValue = p
            if (p >= 1f) {
                claim = Claim.HELD
                Overlay.hold.floatValue = 0f
                val s = BackgroundOperation.state.value
                val owner = s.ownerSessionId
                if (owner != null) {
                    if (s.userStopped) BackgroundOperation.userResume()
                    BackgroundOperation.beginTakeover(owner)
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
            } else main.postDelayed(this, 16L)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        AppLogger.info("Overlay", "window visibility ${if (visibility == VISIBLE) "visible" else "hidden ($visibility)"}" +
            if (!Overlay.hostedByA11y) "（app overlay：系统可能因副屏上的安全页面隐藏它；打开无障碍可避免）" else "")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        // The main screen turned: keep the window (or its tab) on screen.
        post { if (Overlay.docked.value) Overlay.dock(Overlay.dockRight.value) else Overlay.settle() }
    }

    private fun takenOver(): Boolean = BackgroundOperation.state.value.let { it.takeoverSessionId != null && it.takeoverSessionId == it.ownerSessionId }

    private fun stopHold() { main.removeCallbacks(holdTick); Overlay.hold.floatValue = 0f }

    private fun cancelChildren(ev: MotionEvent) {
        if (!childGotDown) return
        childGotDown = false
        val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(c); c.recycle()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val docked = Overlay.docked.value
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                claim = Claim.NONE
                Overlay.stopAnim(); trail.clear()
                downRaw = Offset(ev.rawX, ev.rawY); downLocal = Offset(ev.x, ev.y); lastLocal = downLocal
                downAt = SystemClock.uptimeMillis()
                winX = Overlay.lp?.x ?: 0; winY = Overlay.lp?.y ?: 0
                onControl = !docked && Overlay.hitControl(ev.x, ev.y)
                onPill = !docked && Overlay.pillRect.contains(ev.x, ev.y)
                childGotDown = false
                if (onControl) { childGotDown = true; super.dispatchTouchEvent(ev) }
                else if (!docked && !takenOver() && Overlay.frameRect.contains(ev.x, ev.y)) {
                    Overlay.holdAt.value = downLocal
                    main.postDelayed(holdTick, 150L)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!docked && ev.pointerCount == 2 && claim != Claim.HELD) {
                    stopHold(); cancelChildren(ev)
                    claim = Claim.PINCH
                    pinchDist = dist(ev).coerceAtLeast(1f)
                    pinchW = Overlay.widthPx.floatValue
                    pinchMid = midRaw(ev)
                    winX = Overlay.lp?.x ?: 0; winY = Overlay.lp?.y ?: 0
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val raw = Offset(ev.rawX, ev.rawY)
                lastLocal = Offset(ev.x, ev.y)
                when (claim) {
                    Claim.PINCH -> if (ev.pointerCount >= 2) {
                        val nw = Overlay.clampWidth(pinchW * dist(ev) / pinchDist)
                        val k = nw / pinchW
                        Overlay.widthPx.floatValue = nw
                        Overlay.place((pinchMid.x - (pinchMid.x - winX) * k).roundToInt(), (pinchMid.y - (pinchMid.y - winY) * k).roundToInt())
                    }
                    Claim.MOVE -> {
                        trail.addLast(Triple(SystemClock.uptimeMillis(), raw.x, raw.y)); if (trail.size > 12) trail.removeFirst()
                        val sw = Overlay.screenW()
                        if (docked) {
                            // The tab rides its edge under the finger (with a little give inward); pulled well inward it
                            // opens right under the finger and keeps following — never a jump on release.
                            val right = Overlay.dockRight.value
                            val inward = if (right) sw - raw.x else raw.x
                            if (inward > Overlay.dp(72f)) {
                                Overlay.undockAt(raw.x, raw.y)
                                downRaw = raw; winX = Overlay.lp?.x ?: 0; winY = Overlay.lp?.y ?: 0
                            } else Overlay.dockLive(right, raw.y, (inward - Overlay.dp(Overlay.TAB_W)).coerceIn(0f, Overlay.dp(16f)))
                        } else {
                            val d = raw - downRaw
                            Overlay.place(winX + d.x.roundToInt(), winY + d.y.roundToInt())
                            // Folding follows the finger: within ~110 dp of a side edge the window shrinks toward the
                            // finger; at the edge it is tab-sized and becomes the tab right there, still under the finger.
                            val edge = Overlay.dp(16f); val zone = Overlay.dp(110f)
                            val dist = min(raw.x, sw - raw.x)
                            if (dist <= edge) Overlay.dockLive(raw.x > sw / 2f, raw.y, 0f)
                            else Overlay.shrinkLive(ev.x, ev.y, ((zone - dist) / (zone - edge)).coerceIn(0f, 1f))
                        }
                    }
                    Claim.NONE -> if (hypot(raw.x - downRaw.x, raw.y - downRaw.y) > slop) {
                        stopHold(); cancelChildren(ev)
                        claim = if (!docked && takenOver() && !onControl && !onPill && Overlay.frameRect.contains(downLocal.x, downLocal.y)) Claim.SWIPE else Claim.MOVE
                        if (claim == Claim.MOVE && docked) winX = Overlay.lp?.x ?: 0
                    } else if (onControl) super.dispatchTouchEvent(ev)
                    else -> {}
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> return true
            MotionEvent.ACTION_UP -> {
                stopHold()
                val ms = SystemClock.uptimeMillis() - downAt
                when (claim) {
                    Claim.PINCH -> Overlay.save()
                    Claim.MOVE -> if (docked) {
                        // Let go as a tab: it settles flat on its edge where it is.
                        Overlay.lp?.let { Overlay.dock(Overlay.dockRight.value, it.y) }
                    } else {
                        // A finger that stopped before lifting leaves only old samples: no fling.
                        val stale = trail.lastOrNull()?.let { SystemClock.uptimeMillis() - it.first > 60L } ?: true
                        trail.addLast(Triple(SystemClock.uptimeMillis(), ev.rawX, ev.rawY))
                        val v = if (stale) null else releaseSpeed()
                        Overlay.resetShrink()
                        if (v != null && hypot(v.first, v.second) > flingMin) Overlay.fling(v.first, v.second, ev.rawY) else Overlay.settle(ev.rawY)
                    }
                    Claim.SWIPE -> {
                        val a = pictureToScreen(downLocal.x, downLocal.y, Overlay.frameRect)
                        val b = pictureToScreen(ev.x.coerceIn(Overlay.frameRect.left, Overlay.frameRect.right - 1), ev.y.coerceIn(Overlay.frameRect.top, Overlay.frameRect.bottom - 1), Overlay.frameRect)
                        val owner = BackgroundOperation.state.value.ownerSessionId
                        if (a != null && b != null && owner != null) BackgroundOperation.userSwipe(owner, a.first, a.second, b.first, b.second, ms.toInt().coerceIn(80, 1500))
                    }
                    Claim.HELD -> {}
                    Claim.NONE -> when {
                        onControl -> super.dispatchTouchEvent(ev)
                        docked -> Overlay.undock()
                        takenOver() && !onPill -> {
                            val p = pictureToScreen(downLocal.x, downLocal.y, Overlay.frameRect)
                            val owner = BackgroundOperation.state.value.ownerSessionId
                            if (p != null && owner != null) {
                                if (ms >= 450L) vscreenUserInput(owner) { VScreenShell.longPress(it, p.first, p.second, ms.toInt().coerceAtMost(2000)) }
                                else BackgroundOperation.userTap(owner, p.first, p.second)
                            }
                        }
                        else -> Overlay.hintAt.longValue = SystemClock.uptimeMillis()
                    }
                }
                claim = Claim.NONE; childGotDown = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                stopHold(); cancelChildren(ev)
                if (claim == Claim.MOVE) {
                    if (Overlay.docked.value) Overlay.lp?.let { Overlay.dock(Overlay.dockRight.value, it.y) } else { Overlay.resetShrink(); Overlay.settle() }
                }
                claim = Claim.NONE
                return true
            }
        }
        return true
    }

    private fun dist(ev: MotionEvent): Float = if (ev.pointerCount < 2) 0f else hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))

    private fun midRaw(ev: MotionEvent): Offset {
        // Raw coordinates of the midpoint: the view's screen position plus the local midpoint.
        val loc = IntArray(2); getLocationOnScreen(loc)
        return Offset(loc[0] + (ev.getX(0) + ev.getX(1)) / 2f, loc[1] + (ev.getY(0) + ev.getY(1)) / 2f)
    }
}

private val PAD = 7.dp

/** Report a button's bounds so the gesture frame lets its taps through. */
@Composable
private fun Modifier.control(key: String): Modifier {
    DisposableEffect(key) { onDispose { Overlay.controls.remove(key) } }
    return this.onGloballyPositioned { c -> val b = c.boundsInRoot(); Overlay.controls[key] = RectF(b.left, b.top, b.right, b.bottom) }
}

@Composable
internal fun OverlayContent(fixed: BackgroundOperation.State? = null, picture: (@Composable (String) -> Unit)? = null) {
    val live by BackgroundOperation.state.collectAsState()
    val s = fixed ?: live
    val owner = s.ownerSessionId ?: return
    val mode = modeOf(s)
    if (Overlay.docked.value) { DockTab(mode, Overlay.dockRight.value); return }
    val density = LocalDensity.current
    val w = with(density) { Overlay.widthPx.floatValue.toDp() }
    val h = w / Overlay.aspect()
    val k = (w.value / 200f).coerceIn(0.75f, 1.15f)
    val corner = (14 + 6 * k).dp

    var left by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(owner, mode) {
        while (true) { left = Turns.leftMs(owner) ?: -1L; delay(1000) }
    }
    val title = s.agentTitle ?: when (mode) {
        Mode.ACTING -> s.action?.take(18) ?: Palette.word(mode)
        Mode.STANDBY -> if (left >= 0) "AI 待命 · ${left / 60000}:${"%02d".format((left / 1000) % 60)}" else Palette.word(mode)
        Mode.IDLE -> if (s.powerSaving) "空闲 · 省电暂停" else "空闲"
        else -> Palette.word(mode)
    }

    Box(Modifier.padding(PAD)) {
        Box(Modifier.width(w).height(h).glowBorder(mode, corner, pad = PAD - 1.dp)) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(corner)).background(Palette.ink)
                    .onGloballyPositioned { c -> val b = c.boundsInRoot(); Overlay.frameRect.set(b.left, b.top, b.right, b.bottom) },
            ) {
                if (picture != null) picture(owner) else VScreenLiveFrame(owner, Modifier.fillMaxSize(), interactive = false)
                // A soft scrim under the pill and buttons keeps them readable on white pages.
                Box(Modifier.fillMaxWidth().height((44 * k).dp).background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))))
            }

            // Title pill (drag handle while you hold the screen).
            Row(
                Modifier.align(Alignment.TopStart).padding(start = (8 * k).dp, top = (8 * k).dp).widthIn(max = w - (78 * k).dp)
                    .clip(RoundedCornerShape(50)).background(Color(0xCC12141C))
                    .onGloballyPositioned { c -> val b = c.boundsInRoot(); Overlay.pillRect.set(b.left, b.top, b.right, b.bottom) }
                    .padding(horizontal = (8 * k).dp, vertical = (4 * k).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size((7 * k).dp).clip(CircleShape).background(if (mode == Mode.ACTING) Brush.sweepGradient(Palette.rainbow) else Brush.linearGradient(listOf(Palette.of(mode), Palette.of(mode)))))
                Spacer(Modifier.width((5 * k).dp))
                Text(title, color = Color.White, fontSize = (11 * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            }

            // Buttons, top right.
            Row(Modifier.align(Alignment.TopEnd).padding(end = (6 * k).dp, top = (6 * k).dp)) {
                when (mode) {
                    Mode.ACTING, Mode.STANDBY -> RoundBtn(Icons.Filled.Stop, "停止 AI", Palette.stopped, k, "stop") { BackgroundOperation.userStop() }
                    else -> {
                        val armed = Overlay.closeArmedAt.longValue > 0 && SystemClock.uptimeMillis() - Overlay.closeArmedAt.longValue < 3000
                        RoundBtn(Icons.Filled.Close, "关闭副屏", if (armed) Palette.stopped else Color(0xCC12141C), k, "close") {
                            if (Overlay.closeArmedAt.longValue > 0 && SystemClock.uptimeMillis() - Overlay.closeArmedAt.longValue < 3000) {
                                Overlay.closeArmedAt.longValue = 0L
                                BackgroundOperation.releaseNow(owner)
                            } else Overlay.closeArmedAt.longValue = SystemClock.uptimeMillis()
                        }
                    }
                }
                Spacer(Modifier.width((5 * k).dp))
                RoundBtn(Icons.Filled.OpenInFull, "全屏", Color(0xCC12141C), k, "full") { Overlay.openFull() }
            }

            // Hand back, bottom centre.
            if (mode == Mode.TAKEOVER || mode == Mode.STOPPED) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = (10 * k).dp).control("handback")
                        .clip(RoundedCornerShape(50)).background(Palette.takeover).clickable { BackgroundOperation.userResume() }
                        .padding(horizontal = (12 * k).dp, vertical = (6 * k).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.SmartToy, null, tint = Color(0xFF052E1C), modifier = Modifier.size((14 * k).dp))
                    Spacer(Modifier.width((4 * k).dp))
                    Text("交还 AI", color = Color(0xFF052E1C), fontSize = (12 * k).sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Messages: close armed, hints.
            val armed = Overlay.closeArmedAt.longValue > 0
            var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
            LaunchedEffect(Overlay.hintAt.longValue, Overlay.closeArmedAt.longValue) { now = SystemClock.uptimeMillis(); delay(3100); now = SystemClock.uptimeMillis() }
            val hint = when {
                armed && now - Overlay.closeArmedAt.longValue < 3000 -> "再点一次 × 关闭副屏"
                Overlay.hintAt.longValue > 0 && now - Overlay.hintAt.longValue < 2500 -> "长按 1 秒接管 · 双指缩放 · 拖到边缘收起"
                else -> null
            }
            AnimatedVisibility(hint != null, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
                Text(hint ?: "", color = Color.White, fontSize = (11 * k).sp,
                    modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xDD12141C)).padding(horizontal = 10.dp, vertical = 6.dp))
            }

            // Hold-to-take-over ring.
            val p = Overlay.hold.floatValue
            if (p > 0f) {
                val at = Overlay.holdAt.value
                val ringR = with(density) { (28 * k).dp.toPx() }
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(at.x - Overlay.frameRect.left, at.y - Overlay.frameRect.top)
                    drawCircle(Color(0x66000000), ringR + 6f, c)
                    drawArc(Palette.takeover, -90f, 360f * p, false, topLeft = Offset(c.x - ringR, c.y - ringR),
                        size = androidx.compose.ui.geometry.Size(ringR * 2, ringR * 2), style = Stroke(width = 6f, cap = StrokeCap.Round))
                }
                Icon(Icons.Filled.PanTool, null, tint = Color.White, modifier = Modifier.offset {
                    IntOffset((at.x - Overlay.frameRect.left).roundToInt() - 24, (at.y - Overlay.frameRect.top).roundToInt() - 24) }.size(16.dp))
            }
        }
    }
}

@Composable
private fun RoundBtn(icon: ImageVector, desc: String, bg: Color, k: Float, key: String, onClick: () -> Unit) {
    Box(
        Modifier.size((28 * k).dp).control(key).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size((15 * k).dp)) }
}

/**
 * The folded window: a half capsule peeking from the edge, holding a tiny phone whose outline breathes in the same
 * colour as the big window (rainbow while the AI acts) — the app's own mark, so it reads as "the background screen".
 */
@Composable
private fun DockTab(mode: Mode, right: Boolean) {
    val shape = if (right) RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp) else RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp)
    val glass = listOf(Color(0xF0262A38), Color(0xF014161F))
    Box(
        Modifier.width(Overlay.TAB_W.dp).height(Overlay.TAB_H.dp)
            .shadow(10.dp, shape)
            .clip(shape)
            .background(Brush.horizontalGradient(if (right) glass else glass.reversed()))
            .border(1.dp, Color(0x2EFFFFFF), shape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.offset(x = if (right) 2.dp else (-2).dp).size(width = 11.dp, height = 20.dp)
                .glowBorder(mode, 3.5.dp, width = 1.6.dp, pad = 2.dp)
                .clip(RoundedCornerShape(3.5.dp)).background(Color(0xFF0B0D14)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // A navigation bar at its bottom: it reads as a phone screen, not an empty frame.
            Box(Modifier.padding(bottom = 2.dp).width(5.dp).height(1.2.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xCCFFFFFF)))
        }
    }
}

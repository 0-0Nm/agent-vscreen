package com.kilon.agentvscreen.host

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Keeps the process awake while there is no background screen yet.
 *
 * Observed on the phone (1.1.2): commands hang until the user switches to this app — but only until the first screen
 * exists; from then on the floating window is up and nothing hangs. A process with a visible overlay window counts as
 * "perceptible" to Android and to vendor background managers, which then leave it alone; without one, a background
 * app may be frozen even with its foreground service. In Minis this never showed because the agent app itself was in
 * front.
 *
 * So while the service runs we keep one 1×1 px, fully transparent, untouchable app-overlay window on the main screen.
 * It draws nothing and takes no touches (alpha 0.5 of transparent content stays under Android 12's touch-occlusion
 * limit of 0.8). Needs the overlay permission; without it [Liveness] still reports freezes.
 */
object Presence {
    private var view: View? = null
    private var wm: WindowManager? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile var up = false; private set

    fun start(ctx: Context) = main.post {
        if (view != null) return@post
        val c = ctx.applicationContext
        if (!Settings.canDrawOverlays(c)) { AppLogger.info("Presence", "no overlay permission: keep-alive window not added"); return@post }
        val w = c.getSystemService(WindowManager::class.java) ?: return@post
        val v = View(c)
        val lp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0; alpha = 0.5f; title = "AgentVScreenKeepAlive" }
        runCatching { w.addView(v, lp) }
            .onSuccess { view = v; wm = w; up = true; AppLogger.info("Presence", "keep-alive window added") }
            .onFailure { AppLogger.warning("Presence", "keep-alive window: ${it.message}") }
    }

    fun stop() = main.post {
        val v = view ?: return@post
        runCatching { wm?.removeView(v) }
        view = null; wm = null; up = false
    }
}

package com.kilon.agentvscreen.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Renders the home page to a PNG (only when AVS_SHOTS is set) so the layout can be looked at without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ShotTest {
    @Test fun home() {
        val out = System.getenv("AVS_SHOTS") ?: return
        animateLook = false
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        com.kilon.agentvscreen.host.AppLogger.init(app)
        com.kilon.agentvscreen.api.ApiConfig.init(app)
        runCatching { com.kilon.agentvscreen.core.BackgroundOperation.init(app) }.onFailure { println("BO init: $it") }
        for (night in listOf(false, true)) {
            if (night) org.robolectric.RuntimeEnvironment.setQualifiers("+night")
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            repeat(20) { ShadowLooper.idleMainLooper(); Thread.sleep(50) }
            val v = a.window.decorView
            val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(b))
            FileOutputStream(File(out, if (night) "home-dark.png" else "home.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

/** The floating window in each state, on a fake page (only when AVS_SHOTS is set). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w880dp-h420dp-xhdpi")
class OverlayShotTest {
    @Test fun states() {
        val out = System.getenv("AVS_SHOTS") ?: return
        animateLook = false
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        com.kilon.agentvscreen.host.AppLogger.init(app)
        com.kilon.agentvscreen.api.ApiConfig.init(app)
        val base = com.kilon.agentvscreen.core.BackgroundOperation.State(ownerSessionId = "default", displayId = 5, screenWidth = 1080, screenHeight = 2340)
        val states = listOf(
            "acting" to base.copy(operating = true, agentTitle = "在比价蓝牙耳机"),
            "standby" to base,
            "takeover" to base.copy(takeoverSessionId = "default"),
            "stopped" to base.copy(userStopped = true),
            "idle" to base.copy(idle = true, powerSaving = true),
        )
        val a = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        val density = a.resources.displayMetrics.density
        Overlay.widthPx.floatValue = 150 * density
        a.setContent {
            androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color(0xFFE9EBF2)).padding(12.dp)) {
                states.forEach { (_, st) ->
                    OverlayContent(st) {
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White).padding(top = 48.dp, start = 10.dp, end = 10.dp)) {
                            repeat(9) { i ->
                                androidx.compose.material3.Text("搜索结果第 ${i + 1} 条：降噪耳机", fontSize = 9.sp, color = androidx.compose.ui.graphics.Color.DarkGray)
                                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(10.dp))
                            }
                        }
                    }
                }
            }
        }
        repeat(20) { ShadowLooper.idleMainLooper(); Thread.sleep(30) }
        val v = a.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        FileOutputStream(File(out, "overlay.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

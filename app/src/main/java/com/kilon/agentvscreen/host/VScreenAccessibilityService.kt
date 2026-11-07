package com.kilon.agentvscreen.host

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Read-only window-content access for the virtual display: the element list (text, ids, bounds, editable) and
 * SET_TEXT for typing Chinese. It never performs gestures and never turns on touch exploration, so it coexists with
 * any other accessibility service (an agent's own one included). Optional: without it the tool still works on OCR.
 */
class VScreenAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: VScreenAccessibilityService? = null
        fun getInstance(): VScreenAccessibilityService? = instance
    }

    override fun onServiceConnected() { instance = this; AppLogger.info("A11y", "connected"); com.kilon.agentvscreen.ui.Overlay.rehost() }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; A11yHeal.onLost(applicationContext); com.kilon.agentvscreen.ui.Overlay.rehost(); return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun rootNodesForDisplay(displayId: Int): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            val windows = windowsOnAllDisplays.get(displayId) ?: return emptyList()
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                w.root?.let { out.add(it) }
            }
        } catch (_: Throwable) {}
        return out
    }

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}

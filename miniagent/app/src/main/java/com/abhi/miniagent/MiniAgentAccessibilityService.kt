package com.abhi.miniagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * EXPERIMENTAL. Must be enabled manually by the user via Settings > Accessibility -
 * Android does not allow apps to self-enable this. Kept intentionally minimal: no
 * continuous event handling, just two on-demand capabilities (read screen text, tap)
 * that MainActivity's run_shell-style tool wiring can call into.
 */
class MiniAgentAccessibilityService : AccessibilityService() {

    companion object {
        // Static reference so tool calls can reach the running service without a bound-
        // service connection dance. Null whenever the user hasn't enabled the service.
        var instance: MiniAgentAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty - this service only does on-demand reads/taps below,
        // not continuous monitoring of every event on the device.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    /** Dumps visible on-screen text from whatever window is currently active. */
    fun readScreenText(): String {
        val root = rootInActiveWindow
            ?: return "ERROR: no active window (accessibility service may not actually be enabled)"
        val out = StringBuilder()
        collectText(root, out)
        return out.toString().ifBlank { "(no text found on screen)" }
    }

    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder) {
        node.text?.let { if (it.isNotBlank()) out.append(it).append("\n") }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectText(it, out) }
        }
    }

    /** Taps at absolute screen coordinates via a dispatched gesture. */
    fun tap(x: Float, y: Float): String {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        return if (dispatched) "OK: tapped ($x, $y)" else "ERROR: gesture dispatch failed"
    }
}

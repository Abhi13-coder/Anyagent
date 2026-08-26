package com.abhi.miniagent

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs

/**
 * EXPERIMENTAL first pass. Draws a draggable circular bubble (SYSTEM_ALERT_WINDOW) that
 * expands into a small floating panel on tap. NOTE: the mini chat panel is currently a
 * placeholder shell (close button only) - it is NOT yet wired to the real ChatAdapter/
 * agent loop from MainActivity. That's the natural next increment once this shell is
 * confirmed working (permission grant, drag, open/close) on your device.
 */
class OverlayBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var miniChatView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addBubble()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun addBubble() {
        val bubble = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_agenda) // placeholder icon
            setBackgroundResource(R.drawable.bubble_user)
            val pad = 12.dp()
            setPadding(pad, pad, pad, pad)
        }
        val params = WindowManager.LayoutParams(
            56.dp(), 56.dp(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDrag = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDrag = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > 8 || abs(dy) > 8) isDrag = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDrag) toggleMiniChat()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubble, params)
        bubbleView = bubble
    }

    private fun toggleMiniChat() {
        val existing = miniChatView
        if (existing != null) {
            windowManager.removeView(existing)
            miniChatView = null
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_mini_chat, null)
        val params = WindowManager.LayoutParams(
            300.dp(), 400.dp(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }
        view.findViewById<View>(R.id.btnCloseMiniChat)?.setOnClickListener { toggleMiniChat() }
        windowManager.addView(view, params)
        miniChatView = view
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        miniChatView?.let { runCatching { windowManager.removeView(it) } }
    }
}

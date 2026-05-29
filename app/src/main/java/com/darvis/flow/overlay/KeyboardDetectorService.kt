package com.darvis.flow.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class KeyboardDetectorService : AccessibilityService() {

    companion object {
        const val ACTION_KEYBOARD_SHOWN = "com.darvis.flow.KEYBOARD_SHOWN"
        const val ACTION_KEYBOARD_HIDDEN = "com.darvis.flow.KEYBOARD_HIDDEN"
        var isRunning = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val cls = event.className?.toString() ?: ""
                if (cls.contains("EditText") || cls.contains("AutoComplete") || cls.contains("Input")) {
                    sendBroadcast(Intent(ACTION_KEYBOARD_SHOWN))
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val cls = event.className?.toString() ?: ""
                // When focus moves to a non-input window, hide the bubble
                if (!cls.contains("EditText") && !cls.contains("Input")) {
                    sendBroadcast(Intent(ACTION_KEYBOARD_HIDDEN))
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        sendBroadcast(Intent(ACTION_KEYBOARD_HIDDEN))
    }
}

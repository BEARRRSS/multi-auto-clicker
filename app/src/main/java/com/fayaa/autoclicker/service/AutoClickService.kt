package com.fayaa.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Accessibility Service used to simulate tap gestures on screen.
 *
 * Only one instance runs at a time.  Other components access it via [instance].
 */
class AutoClickService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Perform a single tap at [x], [y] (screen coordinates).
     * Suspends until the gesture completes or is cancelled.
     * Returns true on success, false on failure.
     */
    suspend fun tap(x: Float, y: Float): Boolean = suspendCoroutine { cont ->
        val path = Path().apply { moveTo(x, y) }
        // Duration 1ms – shortest valid press to register as a tap
        val stroke = GestureDescription.StrokeDescription(path, 0L, 1L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                cont.resume(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                cont.resume(false)
            }
        }, null)

        if (!dispatched) cont.resume(false)
    }

    companion object {
        /** Nullable singleton — null when the service is not enabled/running. */
        @Volatile
        var instance: AutoClickService? = null
            private set

        /** Returns true if the Accessibility Service is currently connected. */
        fun isEnabled(): Boolean = instance != null
    }
}

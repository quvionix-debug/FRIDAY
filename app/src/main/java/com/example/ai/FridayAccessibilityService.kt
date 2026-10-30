package com.example.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * FridayAccessibilityService: Hardware-level automation core for FRIDAY.
 *
 * Provides physical automation capabilities on modern Android:
 * 1. Screen wake and automated PIN unlock flow ("2580") via gestures and node inspection.
 * 2. Background app launches (immune to Android's Background Activity Launch restrictions).
 * 3. System global actions: Home, Back, Recents, Notifications, Take Screenshot, Lock Screen.
 */
class FridayAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FridayAccessibility"
        var instance: FridayAccessibilityService? = null
            private set

        val isServiceRunning: Boolean
            get() = instance != null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "FridayAccessibilityService connected and ready.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Can be used to monitor window state transitions
    }

    override fun onInterrupt() {
        Log.w(TAG, "FridayAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    fun unlockPhoneWithPin(pin: String = "226263", onComplete: ((Boolean) -> Unit)? = null) {
        Log.d(TAG, "Initiating hardware PIN unlock protocol (secure silent execution)")

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val wasScreenOff = !powerManager.isInteractive

        // 1. Wake screen if dark
        if (wasScreenOff) {
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "friday:acc_unlock_wake",
            )
            wakeLock.acquire(5000L)

            // Force turn screen on via MainActivity window flags
            val wakeIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra("EXTRA_WAKE_SCREEN", true)
            }
            try {
                startActivity(wakeIntent)
            } catch (_: Exception) {}
        }

        // Wait for screen to power on completely before swiping
        val wakeDelay = if (wasScreenOff) 650L else 100L
        mainHandler.postDelayed({
            performSwipeAndType(pin, onComplete)
        }, wakeDelay)
    }

    private fun performSwipeAndType(pin: String, onComplete: ((Boolean) -> Unit)?) {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        // 2. Perform swipe-up gesture to display keyguard lockpad
        val swipePath = Path().apply {
            moveTo(width / 2f, height * 0.85f)
            lineTo(width / 2f, height * 0.20f)
        }

        val swipeGesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 250))
            .build()

        dispatchGesture(swipeGesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                // Keyguard pad animated into view, start typing PIN digits
                mainHandler.postDelayed({
                    enterPinSequence(pin, width, height, 0, onComplete)
                }, 450L)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                // Fallback attempt
                mainHandler.postDelayed({
                    enterPinSequence(pin, width, height, 0, onComplete)
                }, 350L)
            }
        }, null)
    }

    private fun enterPinSequence(
        pin: String,
        width: Float,
        height: Float,
        index: Int,
        onComplete: ((Boolean) -> Unit)?
    ) {
        if (index >= pin.length) {
            Log.d(TAG, "All PIN digits entered successfully.")
            // After entering 6 digits, optionally press Enter/OK if device requires it
            mainHandler.postDelayed({
                clickEnterOrOkButton(width, height)
                onComplete?.invoke(true)
            }, 250L)
            return
        }

        val digit = pin[index]
        tapDigit(digit, width, height) {
            // Tap next digit with 200ms interval (ensures consecutive identical digits like 2-2 register distinctly)
            mainHandler.postDelayed({
                enterPinSequence(pin, width, height, index + 1, onComplete)
            }, 200L)
        }
    }

    private fun tapDigit(digit: Char, width: Float, height: Float, onTapped: () -> Unit) {
        // Attempt 1: Node-based click
        val clickedNode = clickNodeByDigit(rootInActiveWindow, digit.toString())
        if (clickedNode) {
            onTapped()
            return
        }

        // Attempt 2: Mathematical Keypad Coordinate Matrix
        // Standard Android Keypad layout coordinates:
        val targetPoint = getKeypadCoordinateForDigit(digit, width, height)
        val clickPath = Path().apply {
            moveTo(targetPoint.x, targetPoint.y)
        }
        val clickGesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
            .build()

        dispatchGesture(clickGesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onTapped()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onTapped()
            }
        }, null)
    }

    private fun clickNodeByDigit(root: AccessibilityNodeInfo?, digitStr: String): Boolean {
        if (root == null) return false

        // Check if current node matches digit
        val text = root.text?.toString() ?: ""
        val desc = root.contentDescription?.toString() ?: ""
        val viewId = root.viewIdResourceName ?: ""

        if ((text == digitStr || desc.contains(digitStr) || viewId.endsWith("key$digitStr") || viewId.endsWith("digit$digitStr"))
            && root.isClickable
        ) {
            root.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }

        // Recurse children
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (clickNodeByDigit(child, digitStr)) {
                return true
            }
        }
        return false
    }

    private fun getKeypadCoordinateForDigit(digit: Char, width: Float, height: Float): PointF {
        // Keypad center horizontal columns
        val col1 = width * 0.25f
        val col2 = width * 0.50f
        val col3 = width * 0.75f

        // Keypad vertical row positions
        val row1 = height * 0.58f // 1, 2, 3
        val row2 = height * 0.66f // 4, 5, 6
        val row3 = height * 0.74f // 7, 8, 9
        val row4 = height * 0.82f // 0

        return when (digit) {
            '1' -> PointF(col1, row1)
            '2' -> PointF(col2, row1)
            '3' -> PointF(col3, row1)
            '4' -> PointF(col1, row2)
            '5' -> PointF(col2, row2)
            '6' -> PointF(col3, row2)
            '7' -> PointF(col1, row3)
            '8' -> PointF(col2, row3)
            '9' -> PointF(col3, row3)
            '0' -> PointF(col2, row4)
            else -> PointF(col2, row4)
        }
    }

    private fun clickEnterOrOkButton(width: Float, height: Float) {
        val root = rootInActiveWindow
        if (root != null && clickEnterNode(root)) return

        // Fallback: Dispatch tap to bottom right (typical Enter/Checkmark button position)
        val enterPoint = PointF(width * 0.75f, height * 0.82f)
        val clickPath = Path().apply { moveTo(enterPoint.x, enterPoint.y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun clickEnterNode(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val text = root.text?.toString()?.lowercase(Locale.getDefault()) ?: ""
        val desc = root.contentDescription?.toString()?.lowercase(Locale.getDefault()) ?: ""
        val viewId = root.viewIdResourceName?.lowercase(Locale.getDefault()) ?: ""

        if (((text == "ok") || (text == "enter") || (text == "done") || desc.contains("enter") || desc.contains("confirm") || viewId.contains("enter") || viewId.contains("ok"))
            && root.isClickable
        ) {
            root.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (clickEnterNode(child)) return true
        }
        return false
    }

    // ----------------------------------------------------------------------------------
    // GLOBAL SYSTEM AUTOMATION ACTIONS
    // ----------------------------------------------------------------------------------

    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun performRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun performNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun performTakeScreenshot(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            false
        }
    }

    fun performLockScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }
    }

    // ----------------------------------------------------------------------------------
    // BACKGROUND APP LAUNCHER (Immune to BAL Restrictions)
    // ----------------------------------------------------------------------------------

    fun launchAppByPackage(packageName: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            startActivity(launchIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app from accessibility: ${e.message}")
            false
        }
    }
}

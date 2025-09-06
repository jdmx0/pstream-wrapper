package com.example.pstream

import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.sign

/**
 * Cursor controller with Choreographer-driven physics and state management.
 * Handles FOCUS_MODE vs CURSOR_MODE, smooth movement, and event injection.
 */
class CursorController(
    private val context: Context,
    private val webView: WebView,
    private val cursorOverlay: CursorOverlay
) : Choreographer.FrameCallback, JSBridge.Callback {

    enum class Mode {
        FOCUS_MODE,  // Native TV focus navigation
        CURSOR_MODE  // DPAD moves visual cursor
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private val idleHandler = Handler(Looper.getMainLooper())

    // Physics state
    private var velocityX = 0f
    private var velocityY = 0f
    private var lastFrameNs: Long = 0
    private var isFrameScheduled = false

    // Input state
    private var moveLeft = false
    private var moveRight = false
    private var moveUp = false
    private var moveDown = false
    private var lastInputTime = 0L

    // Configuration from resources
    private val maxSpeedPxS: Float
    private val accelPxS2: Float
    private val frictionPxS2: Float
    private val edgeMarginPx: Float

    // Mode and state
    private var currentMode = Mode.FOCUS_MODE
    private var previousMode = Mode.FOCUS_MODE
    private var isImeVisible = false
    private var isDomEditableFocused = false
    private var isFullscreenVideo = false
    private var isVideoPlaying = false

    // Edge scrolling
    private val overScroller = OverScroller(context)
    private var lastScrollX = 0
    private var lastScrollY = 0

    private val idleTimeoutMs: Long = try {
        context.resources.getInteger(R.integer.cursor_idle_timeout_ms).toLong()
    } catch (_: Exception) { 8000L }
    private val idleRunnable = Runnable {
        if (isFullscreenVideo) {
            cursorOverlay.hide()
        }
    }

    // IME/Focus watchers
    private val imeWatcher = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {}
    }

    init {
        val resources = context.resources
        val density = resources.displayMetrics.density

        maxSpeedPxS = resources.getInteger(R.integer.max_speed_px_s).toFloat() * 1.10f
        accelPxS2 = resources.getInteger(R.integer.accel_px_s2).toFloat() * 1.10f
        frictionPxS2 = resources.getInteger(R.integer.friction_px_s2).toFloat()
        edgeMarginPx = resources.getInteger(R.integer.edge_margin_dp) * density

        // Initialize cursor position
        val displayMetrics = resources.displayMetrics
        val initialX = displayMetrics.widthPixels / 2f
        val initialY = displayMetrics.heightPixels / 2f
        cursorOverlay.setPosition(initialX, initialY)

        // Start in CURSOR_MODE for immediate functionality
        updateMode(Mode.CURSOR_MODE)
    }

    // ===== MODE MANAGEMENT =====

    fun getCurrentMode(): Mode = currentMode

    fun toggleMode() {
        val newMode = if (currentMode == Mode.FOCUS_MODE) Mode.CURSOR_MODE else Mode.FOCUS_MODE
        updateMode(newMode)
    }

    fun forceFocusMode() {
        updateMode(Mode.FOCUS_MODE)
    }

    fun forceCursorMode() {
        updateMode(Mode.CURSOR_MODE)
    }

    fun forceCursorVisible() {
        cursorOverlay.show()
        cursorOverlay.invalidate()
        android.util.Log.d(TAG, "Cursor forced visible")
    }

    private fun updateMode(newMode: Mode) {
        if (currentMode == newMode) return

        previousMode = currentMode
        currentMode = newMode

        when (newMode) {
            Mode.FOCUS_MODE -> {
                cursorOverlay.hide()
                stopMovement()
                // Ensure frame loop is stopped
                choreographer.removeFrameCallback(this)
                isFrameScheduled = false
            }
            Mode.CURSOR_MODE -> {
                cursorOverlay.show()
                // Force immediate visibility update
                cursorOverlay.invalidate()
                startFrameLoop()
            }
        }

        // Log mode changes for debugging
        android.util.Log.d(TAG, "Mode changed: $previousMode -> $currentMode")
        android.util.Log.d(TAG, "Cursor overlay visible: ${cursorOverlay.isVisible()}")
    }

    // ===== INPUT HANDLING =====

    fun onKeyDown(keyCode: Int): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                moveLeft = true
                onInputChanged()
                return currentMode == Mode.CURSOR_MODE
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                moveRight = true
                onInputChanged()
                return currentMode == Mode.CURSOR_MODE
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                moveUp = true
                onInputChanged()
                return currentMode == Mode.CURSOR_MODE
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveDown = true
                onInputChanged()
                return currentMode == Mode.CURSOR_MODE
            }
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER -> {
                if (currentMode == Mode.CURSOR_MODE) {
                    performClick()
                    // When clicking inputs, temporarily hide cursor to allow IME usability
                    // It will auto-restore when focus leaves the field.
                    lastInputTime = System.nanoTime()
                    return true
                }
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                toggleMode()
                return true
            }
        }
        return false
    }

    fun onKeyUp(keyCode: Int): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                moveLeft = false
                onInputChanged()
                return currentMode == Mode.CURSOR_MODE
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                moveRight = false
                onInputChanged()
                return currentMode == Mode.CURSOR_MODE
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                moveUp = false
                onInputChanged()
                return currentMode == Mode.CURSOR_MODE
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveDown = false
                onInputChanged()
                return currentMode == Mode.CURSOR_MODE
            }
        }
        return false
    }

    private fun onInputChanged() {
        lastInputTime = System.nanoTime()
        // reset idle hide timer
        idleHandler.removeCallbacks(idleRunnable)
        idleHandler.postDelayed(idleRunnable, idleTimeoutMs)
        if (currentMode == Mode.CURSOR_MODE && !isFrameScheduled) {
            startFrameLoop()
        }
    }

    // ===== PHYSICS & ANIMATION =====

    override fun doFrame(frameTimeNanos: Long) {
        isFrameScheduled = false

        val dtNs = if (lastFrameNs > 0) frameTimeNanos - lastFrameNs else 0
        lastFrameNs = frameTimeNanos

        // Clamp dt to prevent large jumps (e.g., after pause/resume)
        val dtS = (dtNs / 1_000_000_000f).coerceIn(0f, 0.05f) // clamp to 50ms

        if (currentMode == Mode.CURSOR_MODE && !shouldHideCursor()) {
            updatePhysics(dtS)
            updateCursorPosition(dtS)
            checkEdgeScrolling(dtS)
        }

        // Continue loop if we have movement or are decelerating
        if (shouldContinueFrameLoop()) {
            scheduleNextFrame()
        } else {
            lastFrameNs = 0
        }
    }

    private fun updatePhysics(dtS: Float) {
        val inputX = (if (moveRight) 1f else 0f) + (if (moveLeft) -1f else 0f)
        val inputY = (if (moveDown) 1f else 0f) + (if (moveUp) -1f else 0f)

        // Apply acceleration
        if (inputX != 0f) {
            velocityX += inputX * accelPxS2 * dtS
        } else {
            // Apply friction
            velocityX = applyFriction(velocityX, dtS)
        }

        if (inputY != 0f) {
            velocityY += inputY * accelPxS2 * dtS
        } else {
            velocityY = applyFriction(velocityY, dtS)
        }

        // Clamp velocity
        velocityX = velocityX.coerceIn(-maxSpeedPxS, maxSpeedPxS)
        velocityY = velocityY.coerceIn(-maxSpeedPxS, maxSpeedPxS)
    }

    private fun applyFriction(velocity: Float, dtS: Float): Float {
        val frictionForce = frictionPxS2 * dtS
        return if (abs(velocity) <= frictionForce) {
            0f
        } else {
            velocity - sign(velocity) * frictionForce
        }
    }

    private fun updateCursorPosition(dtS: Float) {
        val currentPos = cursorOverlay.getPosition()
        val newX = currentPos.x + velocityX * dtS
        val newY = currentPos.y + velocityY * dtS

        // Clamp to screen bounds
        val displayMetrics = context.resources.displayMetrics
        val clampedX = newX.coerceIn(0f, displayMetrics.widthPixels.toFloat())
        val clampedY = newY.coerceIn(0f, displayMetrics.heightPixels.toFloat())

        cursorOverlay.setPosition(clampedX, clampedY)
    }

    private fun checkEdgeScrolling(dtS: Float) {
        val pos = cursorOverlay.getPosition()
        val dm = context.resources.displayMetrics

        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()

        var sx = 0f
        var sy = 0f

        // Horizontal edge scroll (rare for most pages)
        if (pos.x <= edgeMarginPx && (moveLeft || moveRight)) {
            val t = (edgeMarginPx - pos.x) / edgeMarginPx
            val speed = (kotlin.math.abs(velocityX) * 0.6f + 250f).coerceAtMost(900f)
            sx = -ease(t) * speed * dtS
        } else if (pos.x >= w - edgeMarginPx && (moveLeft || moveRight)) {
            val t = (pos.x - (w - edgeMarginPx)) / edgeMarginPx
            val speed = (kotlin.math.abs(velocityX) * 0.6f + 250f).coerceAtMost(900f)
            sx = ease(t) * speed * dtS
        }

        // Vertical edge scroll with gentle inertia and clamp to readable speed
        if (pos.y <= edgeMarginPx && (moveUp || moveDown)) {
            val t = (edgeMarginPx - pos.y) / edgeMarginPx
            val speed = (kotlin.math.abs(velocityY) * 0.6f + 220f).coerceAtMost(600f)
            sy = -ease(t) * speed * dtS
        } else if (pos.y >= h - edgeMarginPx && (moveUp || moveDown)) {
            val t = (pos.y - (h - edgeMarginPx)) / edgeMarginPx
            val speed = (kotlin.math.abs(velocityY) * 0.6f + 220f).coerceAtMost(600f)
            sy = ease(t) * speed * dtS
        }

        if (sx != 0f || sy != 0f) {
            val sxInt = when {
                sx > 0f && sx < 1f -> 1
                sx < 0f && sx > -1f -> -1
                else -> sx.toInt()
            }
            val syInt = when {
                sy > 0f && sy < 1f -> 1
                sy < 0f && sy > -1f -> -1
                else -> sy.toInt()
            }
            webView.scrollBy(sxInt, syInt)
        }
    }

    private fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x
    }

    // ===== EVENT INJECTION =====

    private fun performClick() {
        val pos = cursorOverlay.getPosition()

        // Convert to webview coordinates
        val webViewPos = intArrayOf(0, 0)
        webView.getLocationOnScreen(webViewPos)

        val clickX = pos.x - webViewPos[0]
        val clickY = pos.y - webViewPos[1]

        // Inject motion events
        val downTime = android.os.SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN,
            clickX, clickY, 0
        )
        val upEvent = MotionEvent.obtain(
            downTime, downTime + 70, MotionEvent.ACTION_UP,
            clickX, clickY, 0
        )

        webView.dispatchTouchEvent(downEvent)
        webView.dispatchTouchEvent(upEvent)

        // Hint-focus editables under tap for better IME handoff.
        // Pass absolute screen coords; JS converts to CSS px and uses elementFromPoint.
        try {
            val screenPos = IntArray(2)
            webView.getLocationOnScreen(screenPos)
            val absX = screenPos[0] + clickX
            val absY = screenPos[1] + clickY
            JSBridge.focusEditableAtPixels(webView, absX, absY)
            JSBridge.clickAtPixels(webView, absX, absY)
            // Site-specific assist: prefer focusing the known search input if tapped within it
            JSBridge.focusSelectorIfPointInside(
                webView,
                "#root > div > div > div.flex.min-h-screen.flex-col > div.flex.min-h-screen.flex-col > div > div.mb-2 > div.mx-auto.w-\\[600px\\].max-w-full.px-8.sm\\:px-0 > div > div.relative.h-20.z-30 > div > div > div > div > div.flex.flex-1.flex-col.relative > div.relative > input",
                absX,
                absY
            )
        } catch (_: Throwable) {}

        downEvent.recycle()
        upEvent.recycle()
    }

    // ===== IME/FOCUS HANDLING =====

    override fun onDomFocusChanged(hasFocus: Boolean) {
        mainHandler.post {
            isDomEditableFocused = hasFocus
            updateCursorVisibility()
        }
    }

    override fun onVideoStateChanged(fullscreen: Boolean, playing: Boolean) {
        mainHandler.post {
            isFullscreenVideo = fullscreen
            isVideoPlaying = playing
            // Leaving fullscreen: ensure cursor is visible again
            if (!isFullscreenVideo) {
                cursorOverlay.show()
            }
        }
    }

    fun onImeVisibilityChanged(visible: Boolean) {
        isImeVisible = visible
        updateCursorVisibility()
    }

    private fun shouldHideCursor(): Boolean {
        // Hide only while IME shown or editable focused; media idle handled by state
        return isImeVisible || isDomEditableFocused
    }

    private fun updateCursorVisibility() {
        if (shouldHideCursor()) {
            if (currentMode == Mode.CURSOR_MODE) {
                previousMode = Mode.CURSOR_MODE
                forceFocusMode()
            }
        } else {
            // Restore previous mode if we were in cursor mode before IME
            if (previousMode == Mode.CURSOR_MODE && currentMode == Mode.FOCUS_MODE) {
                forceCursorMode()
            }
        }
    }

    // ===== FRAME LOOP MANAGEMENT & IDLE =====

    private fun startFrameLoop() {
        if (!isFrameScheduled && currentMode == Mode.CURSOR_MODE) {
            isFrameScheduled = true
            try {
                choreographer.postFrameCallback(this)
                android.util.Log.d(TAG, "Frame loop started")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to start frame loop", e)
                isFrameScheduled = false
            }
        }
    }

    private fun scheduleNextFrame() {
        if (!isFrameScheduled && currentMode == Mode.CURSOR_MODE) {
            isFrameScheduled = true
            try {
                choreographer.postFrameCallback(this)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to schedule next frame", e)
                isFrameScheduled = false
            }
        }
    }

    private fun shouldContinueFrameLoop(): Boolean {
        return currentMode == Mode.CURSOR_MODE &&
               (moveLeft || moveRight || moveUp || moveDown ||
                abs(velocityX) > 0.1f || abs(velocityY) > 0.1f)
    }

    private fun stopMovement() {
        moveLeft = false
        moveRight = false
        moveUp = false
        moveDown = false
        velocityX = 0f
        velocityY = 0f
    }

    // ===== SNAP-TO FUNCTIONALITY =====

    fun snapToNearestClickable() {
        if (currentMode != Mode.CURSOR_MODE) return

        val pos = cursorOverlay.getPosition()
        JSBridge.findNearestClickable(webView, pos.x, pos.y) { x, y ->
            cursorOverlay.setPosition(x, y)
        }
    }

    override fun onSnapToResult(x: Float, y: Float) {
        mainHandler.post {
            cursorOverlay.setPosition(x, y)
        }
    }

    // ===== LIFECYCLE =====

    fun onResume() {
        lastFrameNs = 0
        if (currentMode == Mode.CURSOR_MODE) {
            startFrameLoop()
        }
    }

    fun onPause() {
        choreographer.removeFrameCallback(this)
        isFrameScheduled = false
    }

    fun onDestroy() {
        choreographer.removeFrameCallback(this)
        overScroller.abortAnimation()
    }

    companion object {
        private const val TAG = "CursorController"
    }
}

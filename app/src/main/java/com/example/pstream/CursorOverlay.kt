package com.example.pstream

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withTranslation

/**
 * Lightweight cursor overlay that draws a reticle (inner dot + thin ring).
 * Single custom View that handles its own drawing without complex layouts.
 */
class CursorOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = 200 // Semi-transparent for visibility without being intrusive
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        alpha = 180
        strokeWidth = 2f
    }

    private var coreRadiusPx = 16f
    private var ringRadiusPx = 6f
    private var position = PointF(0f, 0f)

    init {
        // Convert dp to px
        val density = context.resources.displayMetrics.density
        coreRadiusPx = context.resources.getInteger(R.integer.cursor_core_radius_dp) * density * 1.07f
        ringRadiusPx = context.resources.getInteger(R.integer.cursor_ring_radius_dp) * density * 1.07f

        // Ensure we only draw when needed
        setWillNotDraw(false)
        // Disable background to avoid overdraw
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (visibility != VISIBLE) return

        canvas.withTranslation(position.x, position.y) {
            // Draw outer ring
            drawCircle(0f, 0f, ringRadiusPx, ringPaint)
            // Draw inner dot
            drawCircle(0f, 0f, coreRadiusPx, paint)
        }
    }

    fun show() {
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            alpha = 1.0f
            invalidate()
            android.util.Log.d("CursorOverlay", "Cursor shown at position: $position")
        }
    }

    fun hide() {
        if (visibility != GONE) {
            visibility = GONE
            invalidate()
            android.util.Log.d("CursorOverlay", "Cursor hidden")
        }
    }

    fun isVisible(): Boolean = visibility == VISIBLE

    fun setPosition(x: Float, y: Float) {
        position.set(x, y)
        if (visibility == VISIBLE) {
            invalidate()
            android.util.Log.d("CursorOverlay", "Position updated to: ($x, $y)")
        }
    }

    fun getPosition(): PointF = PointF(position.x, position.y)

    fun fadeIn() {
        animate()
            .alpha(1.0f)
            .setDuration(200)
            .withStartAction { visibility = VISIBLE }
            .start()
    }

    fun fadeOut() {
        animate()
            .alpha(0.0f)
            .setDuration(200)
            .withEndAction { visibility = GONE }
            .start()
    }
}

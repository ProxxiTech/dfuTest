package com.example.bledfutesteractivity

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Immutable grid snapshot: rows × cols cells, row-major (`cells[row * cols + col]`).
 * Rows = the DFU flow's connection attempts (fast×3 + patient×1); cols = test iterations.
 * Cell values are the CELL_* constants in [DfuGridView].
 */
class DfuGridSnapshot(val rows: Int, val cols: Int, val cells: IntArray)

/**
 * A color-tile map of a DFU stress run. One column per iteration, one row per connection attempt.
 * Replaces the old pie chart with an at-a-glance picture of how every trial is going:
 *
 *   grey   = not done yet          yellow = that connect attempt failed
 *   green  = success               red    = the whole trial failed (all attempts failed, or DFU failed)
 *   blinking = in progress (the active connect attempt, or the DFU running on the connected attempt)
 */
class DfuGridView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var rows = 0
    private var cols = 0
    private var cells: IntArray = IntArray(0)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gapPx = 1f * resources.displayMetrics.density
    private var blinkFraction = 1f

    private val blink = ValueAnimator.ofFloat(0.25f, 1f).apply {
        duration = 550
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { blinkFraction = it.animatedValue as Float; invalidate() }
    }

    fun setGrid(snapshot: DfuGridSnapshot?) {
        if (snapshot == null) {
            rows = 0; cols = 0; cells = IntArray(0)
        } else {
            rows = snapshot.rows; cols = snapshot.cols; cells = snapshot.cells
        }
        if (cells.any { it == CELL_RUNNING }) startBlink() else stopBlink()
        invalidate()
    }

    private fun startBlink() { if (!blink.isStarted) blink.start() }
    private fun stopBlink() { if (blink.isStarted) { blink.cancel(); blinkFraction = 1f } }

    override fun onDetachedFromWindow() { stopBlink(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        if (rows <= 0 || cols <= 0) return
        val cw = width.toFloat() / cols
        val ch = height.toFloat() / rows
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val v = cells.getOrElse(r * cols + c) { CELL_PENDING }
                paint.color = colorFor(v)
                paint.alpha = if (v == CELL_RUNNING) (blinkFraction * 255).toInt() else 255
                canvas.drawRect(
                    c * cw + gapPx, r * ch + gapPx,
                    (c + 1) * cw - gapPx, (r + 1) * ch - gapPx,
                    paint
                )
            }
        }
    }

    private fun colorFor(v: Int): Int = when (v) {
        CELL_RUNNING        -> COLOR_RUNNING
        CELL_ATTEMPT_FAILED -> COLOR_YELLOW
        CELL_SUCCESS        -> COLOR_GREEN
        CELL_FAILED         -> COLOR_RED
        else                -> COLOR_PENDING
    }

    companion object {
        const val CELL_PENDING        = 0   // grey
        const val CELL_RUNNING        = 1   // blinking
        const val CELL_ATTEMPT_FAILED = 2   // yellow
        const val CELL_SUCCESS        = 3   // green
        const val CELL_FAILED         = 4   // red

        private val COLOR_PENDING = Color.parseColor("#D6D6D6")
        private val COLOR_RUNNING = Color.parseColor("#2196F3")
        private val COLOR_YELLOW  = Color.parseColor("#FFC107")
        private val COLOR_GREEN   = Color.parseColor("#4CAF50")
        private val COLOR_RED     = Color.parseColor("#F44336")
    }
}

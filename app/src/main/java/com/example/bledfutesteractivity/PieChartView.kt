package com.example.bledfutesteractivity

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class PieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var successCount   = 0; set(v) { field = v; invalidate() }
    var failCount      = 0; set(v) { field = v; invalidate() }
    var remainingCount = 0; set(v) { field = v; invalidate() }

    private val successColor   = Color.parseColor("#4CAF50")
    private val failColor      = Color.parseColor("#F44336")
    private val remainingColor = Color.parseColor("#9E9E9E")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val oval  = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = 4f
        val size = minOf(w, h) - 2 * pad
        val cx = w / 2f
        val cy = h / 2f
        oval.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
    }

    override fun onDraw(canvas: Canvas) {
        val total = successCount + failCount + remainingCount
        if (total == 0) {
            paint.color = remainingColor
            canvas.drawOval(oval, paint)
            return
        }
        var angle = -90f
        fun arc(count: Int, color: Int) {
            if (count == 0) return
            val sweep = 360f * count / total
            paint.color = color
            canvas.drawArc(oval, angle, sweep, true, paint)
            angle += sweep
        }
        arc(successCount,   successColor)
        arc(failCount,      failColor)
        arc(remainingCount, remainingColor)
    }
}

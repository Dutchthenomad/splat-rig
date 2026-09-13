package com.splatrig.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class CoverageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var coverage: Coverage? = null
        set(value) { field = value; invalidate() }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FFFFFF; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val filled = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC7CFFB2.toInt(); style = Paint.Style.FILL
    }
    private val required = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFB347.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt(); textSize = 22f
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cov = coverage ?: return
        val cols = 16
        val cellW = width / cols.toFloat()
        val cellH = (height - 28f) / 3
        val bands = listOf(Coverage.Band.HIGH, Coverage.Band.MID, Coverage.Band.LOW)
        bands.forEachIndexed { r, band ->
            for (c in 0 until cols) {
                val left = c * cellW + 2
                val top = r * cellH + 2
                val cell = Coverage.Cell(c, band)
                val paint = if (cov.count(cell) > 0) filled else empty
                canvas.drawRect(left, top, left + cellW - 4, top + cellH - 4, paint)
                if (isRequired(c, band)) {
                    canvas.drawRect(left, top, left + cellW - 4, top + cellH - 4, required)
                }
            }
        }
        canvas.drawText("F", cellW * 0.2f, height - 6f, label)
        canvas.drawText("PS", cellW * 4.1f, height - 6f, label)
        canvas.drawText("R", cellW * 8.1f, height - 6f, label)
        canvas.drawText("DS", cellW * 12.1f, height - 6f, label)
    }
    private fun isRequired(yaw: Int, band: Coverage.Band): Boolean {
        val side = when (yaw) {
            15, 0, 1 -> Coverage.Side.FRONT
            in 3..5 -> Coverage.Side.PS
            in 7..9 -> Coverage.Side.REAR
            in 11..13 -> Coverage.Side.DS
            else -> return false
        }
        return when (band) {
            Coverage.Band.MID -> true
            Coverage.Band.HIGH -> side == Coverage.Side.FRONT || side == Coverage.Side.REAR
            Coverage.Band.LOW -> false
        }
    }
}

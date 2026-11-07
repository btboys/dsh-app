package app.dsh.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Static console backdrop: a faint technical grid and a phosphor glow behind the header.
 * Drawn once per layout pass and never invalidated, so it costs nothing while scrolling.
 */
class GridBackdrop(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val step = 34f * density

    private val linePaint = Paint().apply {
        color = 0x12FFFFFF
        strokeWidth = density
        isAntiAlias = false
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 360f * density,
            intArrayOf(0x2E63E6A4, 0x0C63E6A4, 0x0063E6A4),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        canvas.save()
        canvas.translate(width * 0.62f, -60f * density)
        canvas.drawCircle(0f, 0f, 360f * density, glowPaint)
        canvas.restore()

        var x = 0f
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height, linePaint)
            x += step
        }
        var y = 0f
        while (y <= height) {
            canvas.drawLine(0f, y, width, y, linePaint)
            y += step
        }
    }
}

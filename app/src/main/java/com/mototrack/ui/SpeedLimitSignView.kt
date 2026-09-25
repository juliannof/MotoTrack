package com.mototrack.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Señal de tráfico de límite de velocidad (R-301): círculo blanco con aro rojo
 * y el número en negro. Sin dato, aro gris con un guion.
 *
 * Como LeanMeterView, la dibujamos a mano en un Canvas.
 */
class SpeedLimitSignView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var limit = 0          // km/h; 0 = desconocido
    private var exceeded = false
    private var estimated = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    fun setLimit(kmh: Int) {
        if (kmh == limit) return
        limit = kmh
        invalidate()
    }

    /** Límite deducido del tipo de vía (no de una señal real): aro naranja. */
    fun setEstimated(value: Boolean) {
        if (value == estimated) return
        estimated = value
        invalidate()
    }

    /** Si vas por encima del límite el interior se tiñe de rojo claro. */
    fun setExceeded(value: Boolean) {
        if (value == exceeded) return
        exceeded = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = size / 2f
        val known = limit > 0

        ringPaint.color = when {
            !known -> GREY
            estimated -> AMBER
            else -> RED
        }
        canvas.drawCircle(cx, cy, r, ringPaint)

        fillPaint.color = when {
            !known -> WHITE
            exceeded -> LIGHT_RED
            else -> WHITE
        }
        canvas.drawCircle(cx, cy, r * 0.72f, fillPaint)

        val label = if (known) limit.toString() else "—"
        textPaint.color = if (known) Color.BLACK else GREY
        // 2 dígitos llenan más que 3
        textPaint.textSize = r * if (label.length >= 3) 0.78f else 1.0f
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, baseline, textPaint)
    }

    private companion object {
        val RED = Color.parseColor("#D32F2F")
        val LIGHT_RED = Color.parseColor("#FFCDD2")
        val AMBER = Color.parseColor("#F57C00")
        val GREY = Color.parseColor("#666666")
        val WHITE = Color.WHITE
    }
}

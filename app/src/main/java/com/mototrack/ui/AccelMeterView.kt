package com.mototrack.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Vúmetro vertical de aceleración longitudinal: 5 LEDs que se llenan de abajo
 * arriba según la intensidad. Al frenar cambian a ámbar/rojo y aparece un "−".
 *
 * Igual que LeanMeterView, dibujamos a mano en un Canvas.
 */
class AccelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Aceleración (m/s²) que enciende todos los LEDs. */
    private val maxAccel = 7.5f
    private val leds = 5
    private val step = maxAccel / leds   // 1,5 m/s² por LED

    // Positivo = acelerando, negativo = frenando
    private var accel = 0f

    private val density = resources.displayMetrics.density
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val signPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 18f * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }

    private val colorOff = Color.parseColor("#2A2D2F")
    private val colorAccel = Color.parseColor("#00BCD4")
    private val colorAccelHigh = Color.parseColor("#8BC34A")
    private val colorBrake = Color.parseColor("#FFC107")
    private val colorBrakeHigh = Color.parseColor("#F44336")

    fun setAccel(value: Float) {
        accel = value
        invalidate()
    }

    fun reset() = setAccel(0f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((28 * density).toInt(), widthMeasureSpec),
            resolveSize((96 * density).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val braking = accel < 0
        val level = abs(accel)

        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()

        // Arriba, hueco para el signo "−" (solo se dibuja al decelerar)
        val signH = signPaint.textSize + 4 * density
        val gap = 3 * density
        val ledH = (h - signH - gap * (leds - 1)) / leds
        val ledW = min(w, ledH * 3.5f)
        val left = paddingLeft + (w - ledW) / 2f
        val radius = ledH * 0.3f
        val bottom = paddingTop + h

        for (i in 0 until leds) {   // i = 0 es el LED de abajo; se llenan hacia arriba
            val value = (i + 1) * step
            val top = bottom - (i + 1) * ledH - i * gap
            rect.set(left, top, left + ledW, top + ledH)

            val lit = level >= value - step / 2
            ledPaint.color = when {
                !lit -> colorOff
                braking -> if (i < 3) colorBrake else colorBrakeHigh
                else -> if (i < 3) colorAccel else colorAccelHigh
            }
            canvas.drawRoundRect(rect, radius, radius, ledPaint)
        }

        if (braking && level >= step / 2) {
            signPaint.color = if (level >= 3 * step) colorBrakeHigh else colorBrake
            val baseline = paddingTop + signH / 2f - (signPaint.descent() + signPaint.ascent()) / 2f
            canvas.drawText("−", paddingLeft + w / 2f, baseline, signPaint)
        }
    }
}

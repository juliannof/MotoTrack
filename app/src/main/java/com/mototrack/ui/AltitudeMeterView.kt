package com.mototrack.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Vúmetro vertical de altura: LEDs iguales a los de la aceleración y la inclinación,
 * apilados de abajo arriba. El primero es la altura mínima de la ruta, el último la
 * máxima, y la altura actual enciende los LEDs hasta su proporción. Con la moto
 * parada se encienden todos.
 *
 * Igual que LeanMeterView, dibujamos a mano en un Canvas.
 */
class AltitudeMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // 0..1: posición de la altura actual entre la mínima y la máxima de la ruta
    private var fraction = 1f

    private val density = resources.displayMetrics.density
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val rect = RectF()

    private val colorOff = Color.parseColor("#2A2D2F")
    private var colorOn = Color.parseColor("#FF5722")

    /** Color de los LEDs encendidos (naranja normal, azul marino bajo el nivel del mar). */
    fun setColor(color: Int) {
        if (color == colorOn) return
        colorOn = color
        invalidate()
    }

    fun setFraction(value: Float) {
        val v = value.coerceIn(0f, 1f)
        if (v == fraction) return
        fraction = v
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((32 * density).toInt(), widthMeasureSpec),
            resolveSize((110 * density).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        val gap = 3 * density
        // Nº de LEDs según el alto disponible: ~12 dp por LED
        val leds = ((h + gap) / (12 * density)).toInt().coerceIn(4, 30)
        val ledT = (h - gap * (leds - 1)) / leds
        // Como en la aceleración, los LEDs no pasan de ~2,2 veces su grosor
        val ledL = min(w, ledT * 2.2f)
        val left = paddingLeft + (w - ledL) / 2f
        val radius = ledT * 0.4f
        val bottom = paddingTop + h

        // El primer LED es la altura mínima (siempre encendido) y el último la máxima
        val litCount = (1 + fraction * (leds - 1) + 0.5f).toInt().coerceIn(1, leds)

        for (i in 0 until leds) {   // i = 0 es el LED de abajo
            val top = bottom - (i + 1) * ledT - i * gap
            rect.set(left, top, left + ledL, top + ledT)
            if (i < litCount) {
                // Halo suave detrás del LED encendido
                ledPaint.color = colorOn
                ledPaint.alpha = 60
                val halo = 2f * density
                canvas.drawRoundRect(
                    rect.left - halo, rect.top - halo, rect.right + halo, rect.bottom + halo,
                    radius + halo, radius + halo, ledPaint
                )
                ledPaint.alpha = 255
            } else {
                ledPaint.color = colorOff
            }
            canvas.drawRoundRect(rect, radius, radius, ledPaint)
        }

        // El último LED es la altura máxima: si no se ha llegado a ella, va contorneado
        if (litCount < leds) {
            val top = bottom - leds * ledT - (leds - 1) * gap
            rect.set(left, top, left + ledL, top + ledT)
            peakPaint.color = colorOn
            canvas.drawRoundRect(rect, radius, radius, peakPaint)
        }
    }
}

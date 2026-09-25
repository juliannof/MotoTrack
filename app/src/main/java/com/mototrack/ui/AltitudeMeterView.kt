package com.mototrack.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil
import kotlin.math.min

/**
 * Vúmetro de altura: una fila de LEDs iguales a los de la inclinación. El último LED
 * es la altura máxima alcanzada en la ruta, el primero la mínima, y la altura
 * actual enciende los LEDs hasta su proporción. Con la moto parada se encienden todos.
 *
 * Igual que LeanMeterView, dibujamos a mano en un Canvas.
 */
class AltitudeMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // 0..1: parte de la altura máxima que representa la altura actual
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

    /** Color de los LEDs encendidos (naranja normal, azul bajo el nivel del mar). */
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
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize((16 * density).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        val gap = 3 * density
        // Nº de LEDs según el ancho disponible: ~12 dp por LED (más ancho, más LEDs)
        val leds = ((w + gap) / (12 * density)).toInt().coerceIn(8, 40)
        val ledW = (w - gap * (leds - 1)) / leds
        // Como en la inclinación, los LEDs no pasan de ~2,2 veces su ancho
        val ledH = min(h, ledW * 2.2f)
        val top = paddingTop + (h - ledH) / 2f
        val radius = ledW * 0.4f

        // El primer LED es la altura mínima (siempre encendido) y el último la máxima
        val litCount = (1 + fraction * (leds - 1) + 0.5f).toInt().coerceIn(1, leds)

        for (i in 0 until leds) {
            val left = paddingLeft + i * (ledW + gap)
            rect.set(left, top, left + ledW, top + ledH)
            val lit = i < litCount
            if (lit) {
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
            val left = paddingLeft + (leds - 1) * (ledW + gap)
            rect.set(left, top, left + ledW, top + ledH)
            peakPaint.color = colorOn
            canvas.drawRoundRect(rect, radius, radius, peakPaint)
        }
    }
}

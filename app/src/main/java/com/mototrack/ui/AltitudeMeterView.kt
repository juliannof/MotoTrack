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
 * Vúmetro vertical de altura, con los mismos LEDs que el resto y la misma lógica del
 * máximo que el de inclinación:
 *  - El primer LED (abajo) está reservado para "por debajo del nivel del mar" y solo se
 *    enciende, en azul marino, con altura negativa.
 *  - Del segundo al último, la escala va de 0 m a la altura máxima de la ruta (con altura
 *    positiva, al menos el segundo LED está encendido): el último LED es ese máximo (su valor ya se ve en el número de debajo).
 *
 * Igual que LeanMeterView, dibujamos a mano en un Canvas.
 */
class AltitudeMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var altitude = 0.0
    private var maxAltitude = 0.0

    private val density = resources.displayMetrics.density
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val colorOff = Color.parseColor("#2A2D2F")
    private var colorOn = Color.parseColor("#FF5722")
    private val colorBelowSea = Color.parseColor("#2C56CC")

    /** Color de los LEDs encendidos sobre el nivel del mar. */
    fun setColor(color: Int) {
        if (color == colorOn) return
        colorOn = color
        invalidate()
    }

    /** [altitude] actual y [max] de la ruta, en metros sobre el nivel del mar (pueden ser negativos). */
    fun set(altitude: Double, max: Double) {
        this.altitude = altitude
        this.maxAltitude = max
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((70 * density).toInt(), widthMeasureSpec),
            resolveSize((110 * density).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val h = (height - paddingTop - paddingBottom).toFloat()
        val gap = 3 * density
        // Nº de LEDs según el alto disponible: ~12 dp por LED (uno reservado abajo)
        val leds = ((h + gap) / (12 * density)).toInt().coerceIn(5, 30)
        val ledT = (h - gap * (leds - 1)) / leds
        // Como en la aceleración, los LEDs no pasan de ~2,2 veces su grosor
        val ledL = min(ledT * 2.2f, 26 * density)
        // Pegado al borde derecho de la vista
        val left = width - paddingRight - ledL
        val radius = ledT * 0.4f
        val bottom = paddingTop + h

        // LEDs 1..leds-1: de 0 m a la máxima. El 0 (abajo) es el de "bajo el nivel del mar".
        val scaleLeds = leds - 1
        val below = altitude < 0
        // Sobre el nivel del mar siempre hay al menos un LED encendido (el primero sobre el mar)
        val litScale = when {
            below || altitude <= 0.0 -> 0
            maxAltitude <= 0.0 -> 1
            else -> (altitude / maxAltitude * scaleLeds + 0.5).toInt().coerceIn(1, scaleLeds)
        }

        for (i in 0 until leds) {   // i = 0 es el LED de abajo
            val top = bottom - (i + 1) * ledT - i * gap
            rect.set(left, top, left + ledL, top + ledT)
            val color: Int
            val lit: Boolean
            if (i == 0) { lit = below; color = colorBelowSea }
            else { lit = i <= litScale; color = colorOn }

            if (lit) {
                // Halo suave detrás del LED encendido
                ledPaint.color = color
                ledPaint.alpha = 60
                val halo = 2f * density
                canvas.drawRoundRect(
                    rect.left - halo, rect.top - halo, rect.right + halo, rect.bottom + halo,
                    radius + halo, radius + halo, ledPaint
                )
                ledPaint.alpha = 255
            } else {
                // El LED de "bajo el nivel del mar" apagado se ve en azul tenue, para reconocerlo
                ledPaint.color = if (i == 0) colorBelowSea else colorOff
                if (i == 0) ledPaint.alpha = 90
            }
            canvas.drawRoundRect(rect, radius, radius, ledPaint)
            ledPaint.alpha = 255
        }
    }
}

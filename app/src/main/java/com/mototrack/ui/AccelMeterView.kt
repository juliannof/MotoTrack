package com.mototrack.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Vúmetro horizontal de aceleración longitudinal, igual que LeanMeterView: LEDs
 * redondeados que se encienden desde el centro, a la derecha al acelerar y a la
 * izquierda al frenar, con halo, barra central y marca del máximo.
 *
 * Igual que LeanMeterView, dibujamos a mano en un Canvas.
 */
class AccelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Aceleración (m/s²) que enciende todos los LEDs de un lado. */
    private val maxAccel = 7.5f
    private val ledsPerSide = 5
    private val step = maxAccel / ledsPerSide   // 1,5 m/s² por LED

    // Positivo = acelerando (derecha), negativo = frenando (izquierda)
    private var accel = 0f
    // Máximo alcanzado en cada sentido (se queda marcado, como el "peak hold")
    private var peakBrake = 0f
    private var peakAccel = 0f

    private val density = resources.displayMetrics.density
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val rect = RectF()

    // Misma paleta que LeanMeterView
    private val colorOff = Color.parseColor("#2A2D2F")
    private val colorGreen = Color.parseColor("#8BC34A")
    private val colorAmber = Color.parseColor("#FFC107")
    private val colorOrange = Color.parseColor("#FF5722")
    private val colorRed = Color.parseColor("#F44336")

    fun setAccel(value: Float) {
        accel = value
        if (value < 0) peakBrake = max(peakBrake, -value) else peakAccel = max(peakAccel, value)
        invalidate()
    }

    fun reset() {
        accel = 0f
        peakBrake = 0f
        peakAccel = 0f
        invalidate()
    }

    /** Color según la intensidad que representa el LED (mismos tramos que la inclinación). */
    private fun colorFor(ledIndex: Int): Int {
        val fraction = (ledIndex + 1f) / ledsPerSide
        return when {
            fraction <= 0.4f -> colorGreen
            fraction <= 0.6f -> colorAmber
            fraction <= 0.8f -> colorOrange
            else -> colorRed
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize((32 * density).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        val cx = paddingLeft + w / 2f
        val gap = 3 * density
        val centerGap = 8 * density
        val ledW = (w - centerGap - gap * (ledsPerSide * 2 - 2)) / (ledsPerSide * 2)
        // Los LEDs no pasan de ~2,2 veces su ancho, para que parezcan LEDs y no barras
        val ledH = min(h, ledW * 2.2f)
        val top = paddingTop + (h - ledH) / 2f
        val radius = ledW * 0.4f

        val brakeLevel = if (accel < 0) -accel else 0f
        val accelLevel = if (accel > 0) accel else 0f

        for (side in arrayOf(-1, 1)) {   // -1 = izquierda (frena), 1 = derecha (acelera)
            val level = if (side < 0) brakeLevel else accelLevel
            val peak = if (side < 0) peakBrake else peakAccel
            val peakIndex = ceil(peak / step).toInt() - 1

            for (i in 0 until ledsPerSide) {
                val value = (i + 1) * step
                // i = 0 es el LED junto al centro; crecen hacia fuera
                val inner = cx + side * (centerGap / 2 + i * (ledW + gap))
                val left = if (side < 0) inner - ledW else inner
                rect.set(left, top, left + ledW, top + ledH)

                // Se enciende al pasar la mitad de su tramo (zona muerta de 0,75 m/s²)
                val lit = level >= value - step / 2
                val color = colorFor(i)

                if (lit) {
                    // Halo suave detrás del LED encendido
                    ledPaint.color = color
                    ledPaint.alpha = 60
                    val halo = 2.5f * density
                    canvas.drawRoundRect(
                        rect.left - halo, rect.top - halo, rect.right + halo, rect.bottom + halo,
                        radius + halo, radius + halo, ledPaint
                    )
                    ledPaint.alpha = 255
                } else {
                    ledPaint.color = colorOff
                }
                canvas.drawRoundRect(rect, radius, radius, ledPaint)

                // Marca del máximo: contorno del color del LED
                if (!lit && i == peakIndex) {
                    peakPaint.color = color
                    canvas.drawRoundRect(rect, radius, radius, peakPaint)
                }
            }
        }

        // Sin aceleración: barra central iluminada, como "moto recta" en la inclinación
        val steady = abs(accel) < step / 2
        val barHalf = 1.5f * density
        rect.set(cx - barHalf, top, cx + barHalf, top + ledH)
        if (steady) {
            ledPaint.color = colorGreen
            ledPaint.alpha = 60
            val halo = 2.5f * density
            canvas.drawRoundRect(
                rect.left - halo, rect.top - halo, rect.right + halo, rect.bottom + halo,
                barHalf + halo, barHalf + halo, ledPaint
            )
            ledPaint.alpha = 255
        } else {
            ledPaint.color = colorOff
        }
        canvas.drawRoundRect(rect, barHalf, barHalf, ledPaint)
    }
}

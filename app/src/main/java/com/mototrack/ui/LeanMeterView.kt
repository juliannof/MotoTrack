package com.mototrack.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Vúmetro de inclinación lateral: una fila de LEDs redondeados que se
 * encienden desde el centro hacia el lado al que se inclina la moto.
 *
 * Es una View "a mano": en vez de componer otras vistas (como harías con
 * componentes en Ionic), dibujamos directamente en un Canvas en onDraw().
 * Android llama a onDraw() cada vez que pedimos invalidate().
 */
class LeanMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Ángulo que enciende todos los LEDs de un lado. */
    private val maxAngle = 60f
    private val ledsPerSide = 12
    private val step = maxAngle / ledsPerSide   // 5° por LED

    // Negativo = izquierda, positivo = derecha
    private var lean = 0f
    // Máximo alcanzado a cada lado (se queda marcado, como el "peak hold")
    private var peakLeft = 0f
    private var peakRight = 0f

    private val density = resources.displayMetrics.density
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    private val colorOff = Color.parseColor("#2A2D2F")
    private val colorGreen = Color.parseColor("#8BC34A")
    private val colorAmber = Color.parseColor("#FFC107")
    private val colorOrange = Color.parseColor("#FF5722")
    private val colorRed = Color.parseColor("#F44336")

    fun setLean(degrees: Float) {
        lean = degrees
        if (degrees < 0) peakLeft = max(peakLeft, -degrees) else peakRight = max(peakRight, degrees)
        invalidate()
    }

    fun reset() {
        lean = 0f
        peakLeft = 0f
        peakRight = 0f
        invalidate()
    }

    /** Color según el ángulo que representa el LED. */
    private fun colorFor(angle: Float) = when {
        angle <= 25f -> colorGreen
        angle <= 40f -> colorAmber
        angle <= 50f -> colorOrange
        else -> colorRed
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Con wrap_content, 56dp de alto; con match_parent/peso, lo que den
        val desired = (56 * density).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val labelH = labelPaint.textSize + 6 * density
        val w = width - paddingLeft - paddingRight
        val cx = paddingLeft + w / 2f
        val gap = 3 * density
        val centerGap = 8 * density
        val ledW = (w - centerGap - gap * (ledsPerSide * 2 - 2)) / (ledsPerSide * 2)
        // Los LEDs no pasan de ~2,2 veces su ancho, para que parezcan LEDs y no barras
        val ledH = min(height - paddingTop - paddingBottom - labelH, ledW * 2.2f)
        val top = paddingTop + (height - paddingTop - paddingBottom - labelH - ledH) / 2f
        val radius = ledW * 0.4f

        val leftLevel = if (lean < 0) -lean else 0f
        val rightLevel = if (lean > 0) lean else 0f

        for (side in arrayOf(-1, 1)) {
            val level = if (side < 0) leftLevel else rightLevel
            val peak = if (side < 0) peakLeft else peakRight
            val peakIndex = ceil(peak / step).toInt() - 1

            for (i in 0 until ledsPerSide) {
                val angle = (i + 1) * step
                // i = 0 es el LED junto al centro; crecen hacia fuera
                val inner = cx + side * (centerGap / 2 + i * (ledW + gap))
                val left = if (side < 0) inner - ledW else inner
                rect.set(left, top, left + ledW, top + ledH)

                // Se enciende al pasar la mitad de su tramo (zona muerta de 2,5°)
                val lit = level >= angle - step / 2
                val color = colorFor(angle)

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

        // Escala bajo los LEDs: 60 · 30 · 0 · 30 · 60
        val labelY = top + ledH + labelH - 2 * density
        canvas.drawText("0", cx, labelY, labelPaint)
        for (deg in intArrayOf(30, 60)) {
            val i = (deg / step).toInt() - 1
            val offset = centerGap / 2 + i * (ledW + gap) + ledW / 2
            canvas.drawText("$deg", cx - offset, labelY, labelPaint)
            canvas.drawText("$deg", cx + offset, labelY, labelPaint)
        }
    }
}

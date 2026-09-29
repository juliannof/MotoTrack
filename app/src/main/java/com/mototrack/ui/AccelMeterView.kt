package com.mototrack.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/**
 * Vúmetro de aceleración longitudinal, en vertical: neutro en el centro, 4 LEDs hacia
 * arriba al acelerar y 4 hacia abajo al frenar (Leve, Normal, Fuerte, Muy Fuerte). No
 * hay número al lado: el propio vúmetro dice intensidad (cuántos LEDs) y sentido (hacia
 * qué lado).
 *
 * Alineados a la derecha: cada LED es más ancho que el anterior según se aleja del
 * centro (de estrecho junto al neutro a ancho en la punta), con el borde derecho
 * siempre en el mismo sitio, para dar sensación de dinamismo, como una llamarada
 * creciendo hacia dentro desde el borde.
 *
 * El pico de cada sentido queda marcado (contorno) pero solo dura PEAK_HOLD_MS: pasado
 * ese tiempo sin superarlo, se apaga en vez de quedar fijo toda la ruta.
 *
 * Igual que LeanMeterView, dibujamos a mano en un Canvas.
 */
class AccelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Aceleración (m/s²) que enciende todos los LEDs de un lado. */
    private val maxAccel = 7.5f
    private val ledsPerSide = 4
    private val step = maxAccel / ledsPerSide   // 1,875 m/s² por LED

    // Anchura relativa de cada LED, de más estrecho (junto al neutro) a más ancho (en la punta)
    private val widthScale = floatArrayOf(1f, 1.4f, 1.9f, 2.5f)

    // Positivo = acelerando (arriba), negativo = frenando (abajo)
    private var accel = 0f
    // Máximo alcanzado en cada sentido y cuándo, para que se apague solo tras PEAK_HOLD_MS
    private var peakUp = 0f
    private var peakDown = 0f
    private var peakUpAtMs = 0L
    private var peakDownAtMs = 0L
    // Modo resumen (ruta parada): niveles máximos de la ruta en vez de la aceleración en vivo
    private var summary = false
    private var summaryUp = 0f
    private var summaryDown = 0f

    private val density = resources.displayMetrics.density
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val rect = RectF()

    // Misma paleta que LeanMeterView
    private val colorOff = Color.parseColor("#2A2D2F")
    private val colorGreen = Color.parseColor("#8BC34A")   // Leve
    private val colorAmber = Color.parseColor("#FFC107")   // Normal
    private val colorOrange = Color.parseColor("#FF5722")  // Fuerte
    private val colorRed = Color.parseColor("#F44336")     // Muy Fuerte
    private val colorInvalid = Color.parseColor("#BDBDBD") // gris claro: sin calibrar todavía

    // Antes de la calibración primaria de la ruta el dato no es de fiar: se muestra en
    // gris claro en vez de con los colores normales
    private var valid = true

    fun setAccel(value: Float) {
        accel = value
        val now = SystemClock.elapsedRealtime()
        if (now - peakUpAtMs > PEAK_HOLD_MS) peakUp = 0f
        if (now - peakDownAtMs > PEAK_HOLD_MS) peakDown = 0f
        if (value > 0 && value >= peakUp) { peakUp = value; peakUpAtMs = now }
        if (value < 0 && -value >= peakDown) { peakDown = -value; peakDownAtMs = now }
        invalidate()
    }

    fun setValid(value: Boolean) {
        if (value == valid) return
        valid = value
        invalidate()
    }

    fun reset() {
        accel = 0f
        peakUp = 0f
        peakDown = 0f
        peakUpAtMs = 0L
        peakDownAtMs = 0L
        summary = false
        invalidate()
    }

    /**
     * Resumen de la ruta al detener: los LEDs quedan encendidos hasta la máxima aceleración
     * (arriba) y la máxima frenada (abajo), en positivo. `reset()` lo quita al empezar otra ruta.
     */
    fun showSummary(maxUp: Float, maxDown: Float) {
        summary = maxUp > 0f || maxDown > 0f
        summaryUp = maxUp
        summaryDown = maxDown
        invalidate()
    }

    /** Color según el nivel que representa el LED (Leve/Normal/Fuerte/Muy Fuerte). */
    private fun colorFor(ledIndex: Int): Int {
        if (!valid) return colorInvalid
        return when (ledIndex) {
            0 -> colorGreen
            1 -> colorAmber
            2 -> colorOrange
            else -> colorRed
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((40 * density).toInt(), widthMeasureSpec),
            resolveSize((150 * density).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        val right = paddingLeft + w   // todos los LEDs alineados a este borde derecho
        val cy = paddingTop + h / 2f
        val gap = 3 * density
        val centerGap = 8 * density
        val ledH = (h - centerGap - gap * (ledsPerSide - 1) * 2) / (ledsPerSide * 2)
        val radius = ledH * 0.4f

        // Anchura de cada LED a partir de la escala relativa: la más ancha (la última,
        // "Muy Fuerte") ocupa todo lo disponible; el resto, proporcional
        val maxWidthScale = widthScale.max()
        val maxLedW = w

        val upLevel = if (summary) summaryUp else if (accel > 0) accel else 0f
        val downLevel = if (summary) summaryDown else if (accel < 0) -accel else 0f

        for (side in arrayOf(1, -1)) {   // 1 = arriba (acelera), -1 = abajo (frena)
            val level = if (side > 0) upLevel else downLevel
            val peak = if (side > 0) peakUp else peakDown

            for (i in 0 until ledsPerSide) {
                val value = (i + 1) * step
                // i = 0 es el LED junto al centro; crecen hacia fuera
                val inner = cy - side * (centerGap / 2 + i * (ledH + gap))
                val top = if (side > 0) inner - ledH else inner
                val ledW = maxLedW * (widthScale[i] / maxWidthScale)
                rect.set(right - ledW, top, right, top + ledH)

                // Se enciende al pasar la mitad de su tramo (zona muerta de medio paso)
                val lit = level >= value - step / 2
                val color = colorFor(i)

                if (lit) {
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

                // Marca del pico (últimos PEAK_HOLD_MS): contorno del color del LED
                val peakIndex = if (peak <= 0f) -1 else ceil(peak / step).toInt() - 1
                if (!lit && peak > 0f && i == peakIndex) {
                    peakPaint.color = color
                    canvas.drawRoundRect(rect, radius, radius, peakPaint)
                }
            }
        }

        // Sin aceleración: barra central iluminada, como "moto recta" en la inclinación
        val steady = !summary && abs(accel) < step / 2
        val barHalf = 1.5f * density
        rect.set(right - maxLedW * (widthScale[0] / maxWidthScale), cy - barHalf, right, cy + barHalf)
        if (steady) {
            ledPaint.color = if (valid) colorGreen else colorInvalid
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

    private companion object {
        const val PEAK_HOLD_MS = 25_000L
    }
}

package com.mototrack.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.text.TextPaint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.min

/**
 * Número de velocidad que llena todo el alto disponible.
 *
 * Un TextView normal reserva sitio para acentos y letras con rabito (la "g"),
 * así que un dígito solo ocupa ~70 % de su alto. Aquí dibujamos nosotros el
 * texto midiendo la altura real de un dígito.
 *
 * El tamaño se calcula para que quepa "888": así no cambia al pasar de 9 a
 * 10 km/h ni de 99 a 100 (el número no "salta" mientras conduces).
 *
 * Hereda de TextView para que el Fragment siga usando `.text = "…"`.
 */
class SpeedDigitsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    private val drawPaint = TextPaint()
    private val digitBounds = Rect()

    override fun onDraw(canvas: Canvas) {
        val value = text?.toString().orEmpty()
        if (value.isEmpty()) return

        val availW = (width - paddingLeft - paddingRight).toFloat()
        val availH = (height - paddingTop - paddingBottom).toFloat()

        // Medir a un tamaño de referencia y escalar
        drawPaint.set(paint)
        drawPaint.color = currentTextColor
        drawPaint.textSize = REFERENCE_SIZE
        drawPaint.getTextBounds("0", 0, 1, digitBounds)
        val scale = min(
            availH / digitBounds.height(),
            availW / drawPaint.measureText(WIDEST)
        )
        drawPaint.textSize = REFERENCE_SIZE * scale

        // Centrado por la caja real del dígito, no por las métricas de la fuente
        val x = paddingLeft + (availW - drawPaint.measureText(value)) / 2f
        val baseline = paddingTop + (availH + digitBounds.height() * scale) / 2f -
            digitBounds.bottom * scale
        canvas.drawText(value, x, baseline, drawPaint)
    }

    private companion object {
        const val REFERENCE_SIZE = 100f
        const val WIDEST = "888"
    }
}

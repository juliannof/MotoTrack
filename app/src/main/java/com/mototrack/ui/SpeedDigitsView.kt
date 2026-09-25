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
    private val glyphBounds = Rect()

    override fun onDraw(canvas: Canvas) {
        val value = text?.toString().orEmpty()
        if (value.isEmpty()) return

        val availW = (width - paddingLeft - paddingRight).toFloat()
        val availH = (height - paddingTop - paddingBottom).toFloat()

        // Medir a un tamaño de referencia y escalar
        drawPaint.set(paint)
        drawPaint.color = currentTextColor
        drawPaint.letterSpacing = 0f
        drawPaint.textSize = REFERENCE_SIZE
        drawPaint.getTextBounds("0", 0, 1, digitBounds)
        val gap = REFERENCE_SIZE * DIGIT_GAP
        // Un solo tamaño siempre, calculado para 3 cifras: no cambia al pasar de 9 a 10 ni de 99 a 100
        val scale = min(availH / digitBounds.height(), availW / inkWidth(WIDEST, gap))
        drawPaint.textSize = REFERENCE_SIZE * scale

        // Cada cifra se coloca por su trazo real, con un hueco fijo entre cifras: en una
        // fuente de cifras tabulares el "1" ocupa lo mismo que el "8" y quedaba despegado
        var x = paddingLeft + (availW - inkWidth(value, gap) * scale) / 2f
        val baseline = paddingTop + (availH + digitBounds.height() * scale) / 2f -
            digitBounds.bottom * scale
        for (c in value) {
            val s = c.toString()
            drawPaint.textSize = REFERENCE_SIZE
            drawPaint.getTextBounds(s, 0, 1, glyphBounds)
            drawPaint.textSize = REFERENCE_SIZE * scale
            canvas.drawText(s, x - glyphBounds.left * scale, baseline, drawPaint)
            x += (glyphBounds.width() + gap) * scale
        }
    }

    /** Ancho del trazo de un texto a tamaño de referencia: cifras pegadas por el trazo, con [gap] entre ellas. */
    private fun inkWidth(t: String, gap: Float): Float {
        drawPaint.textSize = REFERENCE_SIZE
        var w = 0f
        for (c in t) {
            drawPaint.getTextBounds(c.toString(), 0, 1, glyphBounds)
            w += glyphBounds.width()
        }
        return w + gap * (t.length - 1)
    }

    private companion object {
        const val REFERENCE_SIZE = 100f
        const val WIDEST = "888"
        // Hueco entre el trazo de una cifra y el de la siguiente, en fracción del tamaño
        const val DIGIT_GAP = 0.05f
    }
}

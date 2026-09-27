package com.mototrack.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.mototrack.R
import kotlin.math.max
import kotlin.math.min

/**
 * Rótulo de una línea. Si el texto cabe, se queda quieto; si no, se desplaza sin fin y sin
 * saltos: el texto se dibuja dos veces seguidas, separado por un hueco, y cuando la primera
 * copia sale por la izquierda la segunda ya ocupa su sitio (el marquee de Android se corta y
 * se queda vacío un momento al terminar cada vuelta).
 */
class TickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_green)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    }
    private val padH = 12f * density
    private val padV = 5f * density
    private val gap = 48f * density          // hueco entre el final y el principio del texto
    private val speed = 55f * density        // px por segundo

    private var startMs = SystemClock.uptimeMillis()
    private var textWidth = 0f

    var text: CharSequence = ""
        set(value) {
            if (field.toString() == value.toString()) return
            field = value
            textWidth = paint.measureText(value.toString())
            startMs = SystemClock.uptimeMillis()   // el texto nuevo empieza desde el principio
            requestLayout()
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = paint.fontMetrics
        val h = (fm.descent - fm.ascent + 2 * padV).toInt()
        val w = resolveSize((textWidth + 2 * padH).toInt(), widthMeasureSpec)
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        val baseline = padV - paint.fontMetrics.ascent
        val visibleW = width - 2 * padH
        if (textWidth <= visibleW) {
            canvas.drawText(text.toString(), padH, baseline, paint)
            return
        }
        // Desplazamiento continuo: avanza sin parar y vuelve a 0 justo cuando la segunda copia
        // ha llegado donde estaba la primera (imperceptible)
        val period = textWidth + gap
        val offset = ((SystemClock.uptimeMillis() - startMs) / 1000f * speed) % period
        canvas.save()
        canvas.clipRect(padH, 0f, width - padH, height.toFloat())
        canvas.drawText(text.toString(), padH - offset, baseline, paint)
        canvas.drawText(text.toString(), padH - offset + period, baseline, paint)
        canvas.restore()
        postInvalidateOnAnimation()
    }
}

package com.mototrack.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.mototrack.data.RoutePoint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Miniatura del recorrido: el trazado dibujado a escala (sin mapa de fondo), verde → rojo
 * según la velocidad, con un punto verde al inicio y otro rojo al final. Es solo una
 * referencia visual para la lista del historial.
 */
class TrackThumbView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#121212") }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corner = 8f * density

    // Puntos ya proyectados a un plano (x hacia el este, y hacia el sur) y su velocidad
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var speeds = FloatArray(0)
    private var maxSpeed = 1f

    fun setTrack(points: List<RoutePoint>) {
        val valid = points.filter { it.latitude != 0.0 || it.longitude != 0.0 }
        if (valid.size < 2) {
            xs = FloatArray(0); ys = FloatArray(0); speeds = FloatArray(0)
            invalidate(); return
        }
        val lat0 = valid.first().latitude
        val lon0 = valid.first().longitude
        val k = cos(Math.toRadians(lat0))          // los grados de longitud se acortan con la latitud
        xs = FloatArray(valid.size) { ((valid[it].longitude - lon0) * k).toFloat() }
        ys = FloatArray(valid.size) { (-(valid[it].latitude - lat0)).toFloat() }
        speeds = FloatArray(valid.size) { valid[it].speedKmh }
        maxSpeed = max(1f, speeds.max())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), corner, corner, bg)
        if (xs.size < 2) return

        val pad = 12f * density
        val minX = xs.min(); val maxX = xs.max()
        val minY = ys.min(); val maxY = ys.max()
        val spanX = max(maxX - minX, 1e-7f)
        val spanY = max(maxY - minY, 1e-7f)
        // Misma escala en ambos ejes para no deformar el trazado, centrado en la vista
        val scale = min((width - 2 * pad) / spanX, (height - 2 * pad) / spanY)
        val offX = (width - spanX * scale) / 2f
        val offY = (height - spanY * scale) / 2f
        fun px(i: Int) = offX + (xs[i] - minX) * scale
        fun py(i: Int) = offY + (ys[i] - minY) * scale

        for (i in 1 until xs.size) {
            line.color = speedColor(speeds[i] / maxSpeed)
            canvas.drawLine(px(i - 1), py(i - 1), px(i), py(i), line)
        }
        val r = 4f * density
        dot.color = Color.parseColor("#4CAF50"); canvas.drawCircle(px(0), py(0), r, dot)
        dot.color = Color.parseColor("#F44336"); canvas.drawCircle(px(xs.size - 1), py(xs.size - 1), r, dot)
    }

    /** 0 = verde (lento), 0,5 = amarillo, 1 = rojo (la máxima de la ruta). */
    private fun speedColor(t: Float): Int {
        val v = t.coerceIn(0f, 1f)
        val hue = 120f * (1f - v)       // 120° verde → 0° rojo
        return Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.95f))
    }
}

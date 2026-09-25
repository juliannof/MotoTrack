package com.mototrack.utils

import android.graphics.Color
import android.location.Location
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.maps.android.heatmaps.Gradient
import com.google.maps.android.heatmaps.HeatmapTileProvider
import com.google.maps.android.heatmaps.WeightedLatLng

/**
 * Trazado con mapa de calor de la velocidad: de verde (lento) a rojo (rápido) respecto a
 * [maxSpeed]. Los tramos contiguos del mismo tono se agrupan en una sola línea para no
 * crear miles de polilíneas.
 */
object HeatTrail {
    private const val SHADES = 16
    private const val RESAMPLE_M = 20f
    private const val HEAT_RADIUS = 20
    private const val LINE_DARK_VALUE = 0.65f
    private const val THIN_WIDTH = 4f
    // Puntos del degradado de las manchas: donde está el verde, el amarillo y donde empieza el rojo
    private const val GRAD_GREEN = 0.2f
    private const val GRAD_YELLOW = 0.45f
    private const val GRAD_RED = 0.6f
    private const val PEAK_FRACTION = 0.6   // fracción del pico teórico que ya se pinta como rojo

    /** 0 = verde, 0.5 = amarillo, 1 = rojo. */
    private fun color(t: Float, value: Float = 1f) =
        Color.HSVToColor(floatArrayOf(120f * (1f - t), 0.9f, value))

    /** Nivel 0..1 de la escala de las manchas → posición 0..1 verde-rojo según los puntos del degradado. */
    private fun shadeToT(level: Float, likeBlobs: Boolean): Float {
        if (!likeBlobs) return level
        return when {
            level <= GRAD_GREEN -> 0f
            level <= GRAD_YELLOW -> (level - GRAD_GREEN) / (GRAD_YELLOW - GRAD_GREEN) * 0.5f
            level <= GRAD_RED -> 0.5f + (level - GRAD_YELLOW) / (GRAD_RED - GRAD_YELLOW) * 0.5f
            else -> 1f
        }
    }

    /** Dibuja el trazado y devuelve las líneas creadas, por si hay que borrarlas luego. */
    fun draw(
        map: GoogleMap, points: List<LatLng>, speedsKmh: List<Float>, maxSpeed: Float,
        overBlobs: Boolean = false   // línea fina, con la escala de las manchas y más oscura
    ): List<Polyline> {
        val value = if (overBlobs) LINE_DARK_VALUE else 1f
        val width = if (overBlobs) THIN_WIDTH else 10f
        val lines = mutableListOf<Polyline>()
        var run = mutableListOf<LatLng>()
        var runShade = -1
        fun flush() {
            if (run.size >= 2) {
                lines += map.addPolyline(
                    PolylineOptions().addAll(run).width(width).geodesic(true).zIndex(1f)
                        .color(color(shadeToT(runShade / (SHADES - 1f), overBlobs), value))
                )
            }
        }
        for (i in 1 until points.size) {
            // Cada tramo toma el tono de la velocidad media de sus dos extremos
            val v = (speedsKmh[i - 1] + speedsKmh[i]) / 2f
            val rel = (v / maxSpeed).coerceIn(0f, 1f)
            // Sobre las manchas, el mismo peso (velocidad relativa al cuadrado) y la misma escala
            val level = if (overBlobs) (rel * rel / PEAK_FRACTION).toFloat().coerceIn(0f, 1f) else rel
            val shade = (level * (SHADES - 1)).toInt()
            if (shade != runShade) {
                flush()
                // El nuevo tramo arranca en el último punto: sin huecos entre colores
                run = mutableListOf(points[i - 1])
                runShade = shade
            }
            run.add(points[i])
        }
        flush()
        return lines
    }

    /**
     * Mapa de calor de manchas: cada punto pesa según su velocidad, así que el rojo marca
     * donde se iba más rápido. Se borra con `map.clear()`.
     */
    fun drawHeatmap(map: GoogleMap, points: List<LatLng>, speedsKmh: List<Float>) {
        if (points.isEmpty()) return
        // Los puntos se graban por tiempo: parado o despacio se amontonan y a más velocidad se
        // separan, lo que anulaba el peso por velocidad (todo salía igual de rojo). Se remuestrea
        // por distancia para que cada mancha pese solo por la velocidad.
        // El peso es la velocidad relativa al cuadrado: solo lo muy rápido llega a rojo
        val vMax = maxOf(speedsKmh.maxOrNull() ?: 1f, 1f)
        val weighted = mutableListOf<WeightedLatLng>()
        var last: LatLng? = null
        val d = FloatArray(1)
        for (i in points.indices) {
            val p = points[i]
            val prev = last
            if (prev != null) {
                Location.distanceBetween(prev.latitude, prev.longitude, p.latitude, p.longitude, d)
                if (d[0] < RESAMPLE_M) continue
            }
            val rel = (speedsKmh[i] / vMax).coerceIn(0.02f, 1f)
            weighted.add(WeightedLatLng(p, (rel * rel).toDouble()))
            last = p
        }
        val gradient = Gradient(
            // El rojo entra pronto y se mantiene sólido: núcleo rojo definido, bordes suaves
            intArrayOf(color(0f), color(0.5f), color(1f), color(1f)),
            floatArrayOf(GRAD_GREEN, GRAD_YELLOW, GRAD_RED, 0.8f)
        )
        val lat = points[points.size / 2].latitude
        var overlay: TileOverlay? = null

        // La librería normaliza con un máximo propio que en un trazado lineal denso se queda
        // corto y satura todo a rojo. Se fija a mano: la suma de un núcleo gaussiano sobre puntos
        // separados RESAMPLE_M, que depende del zoom (cuántos píxeles hay entre puntos), así que
        // se recalcula cuando la cámara se detiene.
        fun refresh() {
            val zoom = map.cameraPosition.zoom
            val metersPerPx = 156543.03 * cos(Math.toRadians(lat)) / 2.0.pow(zoom.toDouble()) / 2.0
            val spacingPx = RESAMPLE_M / metersPerPx
            val sigma = HEAT_RADIUS / 3.0
            val peak = maxOf(sigma * sqrt(2 * Math.PI) / maxOf(spacingPx, 1.0), 1.0)
            val provider = HeatmapTileProvider.Builder()
                .weightedData(weighted)
                .gradient(gradient)
                .radius(HEAT_RADIUS)
                .opacity(0.9)
                .maxIntensity(peak * PEAK_FRACTION)
                .build()
            overlay?.remove()
            overlay = map.addTileOverlay(TileOverlayOptions().tileProvider(provider).zIndex(0f))
        }
        refresh()
        map.setOnCameraIdleListener { refresh() }
    }
}

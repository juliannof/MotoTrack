package com.mototrack.utils

import android.graphics.Color
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions

/**
 * Trazado con mapa de calor de la velocidad: de verde (lento) a rojo (rápido) respecto a
 * [maxSpeed]. Los tramos contiguos del mismo tono se agrupan en una sola línea para no
 * crear miles de polilíneas.
 */
object HeatTrail {
    private const val SHADES = 16

    /** 0 = verde, 0.5 = amarillo, 1 = rojo. */
    private fun color(t: Float) = Color.HSVToColor(floatArrayOf(120f * (1f - t), 0.9f, 1f))

    /** Dibuja el trazado y devuelve las líneas creadas, por si hay que borrarlas luego. */
    fun draw(map: GoogleMap, points: List<LatLng>, speedsKmh: List<Float>, maxSpeed: Float): List<Polyline> {
        val lines = mutableListOf<Polyline>()
        var run = mutableListOf<LatLng>()
        var runShade = -1
        fun flush() {
            if (run.size >= 2) {
                lines += map.addPolyline(
                    PolylineOptions().addAll(run).width(10f).geodesic(true)
                        .color(color(runShade / (SHADES - 1f)))
                )
            }
        }
        for (i in 1 until points.size) {
            // Cada tramo toma el tono de la velocidad media de sus dos extremos
            val v = (speedsKmh[i - 1] + speedsKmh[i]) / 2f
            val shade = ((v / maxSpeed).coerceIn(0f, 1f) * (SHADES - 1)).toInt()
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
}

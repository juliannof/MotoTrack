package com.mototrack.utils

import com.mototrack.data.RoutePoint
import kotlin.math.abs

/**
 * Cuenta las curvas de una ruta a partir de la inclinación lateral registrada (con signo:
 * negativo = izquierda, positivo = derecha). Solo se calcula al mostrar el informe.
 *
 * Una curva empieza al pasar de [START_DEG] y termina al bajar de [END_DEG] (la diferencia
 * evita contar dos veces un pequeño bamboleo). Solo cuenta si duró al menos [MIN_MS].
 *
 * Por debajo de [MIN_SPEED_KMH] no hay curvas (a pie o parado la inclinación es la postura
 * del móvil), y un hueco de más de [MAX_GAP_MS] entre puntos (sin GPS) cierra la curva en
 * vez de alargarla.
 */
object CurveCounter {
    const val START_DEG = 10f
    const val END_DEG = 6f
    const val MIN_MS = 1_000L
    const val MIN_SPEED_KMH = 15f
    const val MAX_GAP_MS = 3_000L
    // Lecturas por encima de esto son glitches del sensor, igual que en el máximo de inclinación
    private const val MAX_VALID_DEG = 70f

    data class Result(val left: Int, val right: Int) {
        val total get() = left + right
    }

    fun count(points: List<RoutePoint>): Result {
        var left = 0
        var right = 0
        var side = 0          // 0 = fuera de curva, -1 = izquierda, +1 = derecha
        var startMs = 0L
        var lastMs = 0L

        fun close() {
            if (side != 0 && lastMs - startMs >= MIN_MS) { if (side < 0) left++ else right++ }
            side = 0
        }

        var prevMs = Long.MIN_VALUE
        for (p in points.sortedBy { it.timestamp }) {
            if (prevMs != Long.MIN_VALUE && p.timestamp - prevMs > MAX_GAP_MS) close()
            prevMs = p.timestamp
            if (p.speedKmh < MIN_SPEED_KMH) { close(); continue }
            val lean = p.leanAngle
            if (abs(lean) > MAX_VALID_DEG) continue
            when {
                side == 0 -> if (abs(lean) >= START_DEG) {
                    side = if (lean < 0) -1 else 1
                    startMs = p.timestamp
                    lastMs = p.timestamp
                }
                abs(lean) < END_DEG -> { lastMs = p.timestamp; close() }
                abs(lean) >= START_DEG && (lean < 0) != (side < 0) -> {
                    // Cambio de lado sin pasar por la vertical (chicane): cierra una y abre otra
                    close()
                    side = if (lean < 0) -1 else 1
                    startMs = p.timestamp
                    lastMs = p.timestamp
                }
                else -> lastMs = p.timestamp
            }
        }
        close()
        return Result(left, right)
    }
}

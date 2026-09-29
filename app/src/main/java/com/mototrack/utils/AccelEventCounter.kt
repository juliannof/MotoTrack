package com.mototrack.utils

import com.mototrack.data.RoutePoint
import kotlin.math.abs

/**
 * Cuenta las aceleraciones y frenadas de una ruta por nivel (Leve, Normal, Fuerte, Muy fuerte),
 * a partir de la aceleración longitudinal registrada (positiva = acelerando, negativa =
 * frenando). Solo se calcula al mostrar el informe.
 *
 * Los niveles son los de los LEDs del vúmetro de aceleración: 4 tramos de [LEVEL_STEP] m/s²,
 * y un LED se enciende al pasar la mitad de su tramo. Un evento empieza al alcanzar el primer
 * LED ([START_MS2]) y termina al bajar de [END_MS2] o al cambiar de sentido; su nivel es el de
 * su pico. Los puntos se guardan cada segundo, así que un evento muy breve puede quedar
 * infravalorado.
 *
 * Por debajo de [MIN_SPEED_KMH] no hay eventos (la aceleración solo se mide en marcha) y un
 * hueco de más de [MAX_GAP_MS] entre puntos (sin GPS) cierra el evento en vez de alargarlo.
 */
object AccelEventCounter {
    const val LEVELS = 4
    /** m/s² por LED del vúmetro (7,5 m/s² para encenderlos todos, entre 4). */
    const val LEVEL_STEP = 7.5f / LEVELS
    const val START_MS2 = LEVEL_STEP / 2
    const val END_MS2 = 0.5f
    const val MIN_SPEED_KMH = 8f
    const val MAX_GAP_MS = 3_000L
    // Lecturas por encima de esto son glitches del sensor, igual que al guardar la aceleración
    private const val MAX_VALID_MS2 = 12f

    /** Eventos por nivel: índice 0 = Leve … 3 = Muy fuerte. */
    class Result(val accel: IntArray, val brake: IntArray) {
        val totalAccel get() = accel.sum()
        val totalBrake get() = brake.sum()
    }

    /** Nivel (0..3) de un pico de aceleración en valor absoluto, o -1 si no llega al primer LED. */
    fun levelOf(peak: Float): Int =
        if (peak < START_MS2) -1 else (Math.floor((peak / LEVEL_STEP + 0.5f).toDouble()).toInt() - 1).coerceIn(0, LEVELS - 1)

    fun count(points: List<RoutePoint>): Result {
        val accel = IntArray(LEVELS)
        val brake = IntArray(LEVELS)
        var side = 0          // 0 = sin evento, +1 = acelerando, -1 = frenando
        var peak = 0f

        fun close() {
            if (side != 0) {
                val level = levelOf(peak)
                if (level >= 0) { if (side > 0) accel[level]++ else brake[level]++ }
            }
            side = 0
            peak = 0f
        }

        var prevMs = Long.MIN_VALUE
        for (p in points.sortedBy { it.timestamp }) {
            if (prevMs != Long.MIN_VALUE && p.timestamp - prevMs > MAX_GAP_MS) close()
            prevMs = p.timestamp
            if (p.speedKmh < MIN_SPEED_KMH) { close(); continue }
            val a = p.longAccel
            if (abs(a) > MAX_VALID_MS2) continue
            val sign = if (a > 0) 1 else -1
            when {
                side == 0 -> if (abs(a) >= START_MS2) { side = sign; peak = abs(a) }
                abs(a) < END_MS2 -> close()
                sign != side -> {
                    // Pasa de acelerar a frenar (o al revés) sin bajar de END_MS2: cierra uno y abre otro
                    close()
                    if (abs(a) >= START_MS2) { side = sign; peak = abs(a) }
                }
                else -> if (abs(a) > peak) peak = abs(a)
            }
        }
        close()
        return Result(accel, brake)
    }
}

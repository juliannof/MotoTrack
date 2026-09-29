package com.mototrack.utils

import com.mototrack.data.RoutePoint
import kotlin.math.roundToInt

/**
 * Estilo de conducción de una ruta (Paseo, Touring o Deportiva) a partir de las aceleraciones
 * y frenadas por nivel de [AccelEventCounter].
 *
 * Los eventos Leves no cuentan (son el ruido normal de rodar). Cada evento Normal, Fuerte o
 * Muy fuerte, de aceleración o de frenada, suma su peso ([WEIGHTS]); la suma se divide por
 * los km de la ruta y da la intensidad por cada 10 km, para que una ruta larga no puntúe más
 * solo por serlo. La puntuación es esa intensidad en escala 0-100 ([FULL_SCALE_RATE] = 100).
 *
 * Paseo hasta [TOURING_FROM] puntos, Touring hasta [SPORT_FROM] y Deportiva a partir de ahí.
 * Son estimaciones: las 4 rutas de casa al colegio con aceleración guardada (~19 km, tráfico
 * mixto) puntúan 37, 46, 54 y 71, así que todavía no hay ninguna ruta de Paseo con la que
 * comprobar ese umbral. Ajustar aquí con más rutas.
 */
object DrivingStyle {
    /** Colores como los LEDs del vúmetro: verde, ámbar y rojo. */
    enum class Style(val label: String, val colorHex: String) {
        PASEO("Paseo", "#8BC34A"), TOURING("Touring", "#FFC107"), DEPORTIVA("Deportiva", "#F44336")
    }

    class Score(val points: Int, val style: Style) {
        val text get() = "${style.label} · $points"
    }

    /** Peso de cada nivel: Leve, Normal, Fuerte, Muy fuerte. */
    private val WEIGHTS = intArrayOf(0, 1, 4, 8)
    private const val FULL_SCALE_RATE = 60f   // intensidad por 10 km que da 100 puntos
    const val TOURING_FROM = 33
    const val SPORT_FROM = 67
    /** Por debajo de esto no hay ruta que valorar. */
    private const val MIN_KM = 2f

    /** null si la ruta es demasiado corta o no tiene aceleración guardada (rutas antiguas). */
    fun score(points: List<RoutePoint>, distanceKm: Float): Score? {
        if (distanceKm < MIN_KM || points.all { it.longAccel == 0f }) return null
        val events = AccelEventCounter.count(points)
        var weighted = 0
        for (level in 0 until AccelEventCounter.LEVELS) {
            weighted += WEIGHTS[level] * (events.accel[level] + events.brake[level])
        }
        val ratePer10Km = weighted / distanceKm * 10f
        val pts = (ratePer10Km / FULL_SCALE_RATE * 100f).roundToInt().coerceIn(0, 100)
        val style = when {
            pts >= SPORT_FROM -> Style.DEPORTIVA
            pts >= TOURING_FROM -> Style.TOURING
            else -> Style.PASEO
        }
        return Score(pts, style)
    }
}

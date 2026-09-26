package com.mototrack.utils

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * ¿Es de noche en una posición y momento? Se calcula la altura del Sol sobre el horizonte
 * (fórmulas astronómicas simplificadas, error de ~1°): sin red ni tablas de amanecer/atardecer.
 * "Noche" va de la puesta a la salida oficiales del Sol de ese lugar (coincide con Open-Meteo
 * a ~1 min).
 */
object Daylight {
    // Salida/puesta oficial del Sol: el disco toca el horizonte (con la refracción de la atmósfera)
    private const val NIGHT_BELOW_ELEVATION_DEG = -0.833

    fun isNight(lat: Double, lon: Double, timeMs: Long = System.currentTimeMillis()): Boolean =
        sunElevationDeg(lat, lon, timeMs) < NIGHT_BELOW_ELEVATION_DEG

    /** Altura del Sol sobre el horizonte en grados (negativa = bajo el horizonte). */
    fun sunElevationDeg(lat: Double, lon: Double, timeMs: Long): Double {
        val n = timeMs / 86_400_000.0 + 2_440_587.5 - 2_451_545.0      // días desde J2000
        val meanLon = Math.toRadians((280.460 + 0.9856474 * n).mod(360.0))
        val anomaly = Math.toRadians((357.528 + 0.9856003 * n).mod(360.0))
        val eclLon = meanLon + Math.toRadians(1.915) * sin(anomaly) + Math.toRadians(0.020) * sin(2 * anomaly)
        val obliquity = Math.toRadians(23.439 - 0.0000004 * n)

        val declination = asin(sin(obliquity) * sin(eclLon))
        val rightAscension = atan2(cos(obliquity) * sin(eclLon), cos(eclLon))
        val siderealDeg = (280.46061837 + 360.98564736629 * n).mod(360.0)
        val hourAngle = Math.toRadians(siderealDeg + lon) - rightAscension

        val latRad = Math.toRadians(lat)
        val sinElevation = sin(latRad) * sin(declination) + cos(latRad) * cos(declination) * cos(hourAngle)
        return Math.toDegrees(asin(sinElevation))
    }
}

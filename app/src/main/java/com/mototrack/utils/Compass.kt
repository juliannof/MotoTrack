package com.mototrack.utils

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Rumbo de la moto según la brújula del móvil (a partir de la matriz de rotación).
 *
 * Con el móvil en el soporte, la pantalla mira al piloto: "adelante" es el eje z de
 * salida de pantalla en sentido contrario, igual que en la aceleración longitudinal.
 * Con el móvil tumbado (pantalla hacia arriba, p. ej. sobre una mesa) no hay eje z
 * horizontal: se usa hacia dónde apunta la parte superior del móvil.
 */
object Compass {

    // Módulo mínimo del eje en el plano horizontal para fiarse de él
    private const val MIN_HORIZONTAL = 0.5f

    /** Rumbo en grados (0 = norte, 90 = este) o null si el móvil no da una dirección clara. */
    fun azimuth(rotMatrix: FloatArray): Float? {
        // Eje z del móvil (sale de la pantalla) y eje y (parte superior), en coordenadas del mundo
        val zx = rotMatrix[2]; val zy = rotMatrix[5]
        val yx = rotMatrix[1]; val yy = rotMatrix[4]
        val (fx, fy) = when {
            hypot(zx, zy) >= MIN_HORIZONTAL -> -zx to -zy      // en el soporte: adelante = -z
            hypot(yx, yy) >= MIN_HORIZONTAL -> yx to yy        // tumbado: adelante = arriba del móvil
            else -> return null
        }
        return ((Math.toDegrees(atan2(fx, fy).toDouble()) + 360) % 360).toFloat()
    }
}

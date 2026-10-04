package com.mototrack.utils

import android.view.Surface
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Inclinación lateral del móvil a partir de la matriz de rotación del sensor. La usan el
 * servicio de grabación y la calibración en parado, para que las dos midan exactamente lo mismo.
 */
object LeanMath {
    // La R1200RS homologa ~47° de inclinación máxima; más allá es ruido de orientación,
    // no la moto tumbándose (picos vistos de 47,9° y 56,2° que no cuadran ni con la moto
    // ni con el estilo de conducción)
    const val MAX_PLAUSIBLE_LEAN_DEG = 47f
    // Módulo mínimo del "arriba" proyectado en la pantalla (0.5 ≈ pantalla a ≤60° de la vertical)
    const val MIN_SCREEN_VERTICALITY = 0.5f

    /**
     * Con el móvil en un soporte de manillar la pantalla mira al piloto, así que tumbarse en una
     * curva gira el móvil *en el plano de la pantalla*. Se mide el ángulo entre el "arriba" de la
     * pantalla y la vertical real: inclinar la pantalla hacia delante o atrás no lo cambia.
     * Negativo = izquierda, positivo = derecha. null si no hay lectura fiable (móvil fuera del
     * soporte, tumbado, boca abajo…).
     *
     * [rotMatrix] es la matriz 3x3 de SensorManager.getRotationMatrixFromVector y
     * [displayRotation] una de Surface.ROTATION_*.
     */
    fun lateralLeanDegrees(rotMatrix: FloatArray, displayRotation: Int): Float? {
        // Fila 3 de la matriz de rotación = eje "arriba" del mundo expresado
        // en coordenadas del dispositivo (x derecha, y arriba en vertical)
        val ux = rotMatrix[6]
        val uy = rotMatrix[7]

        // Pasar a coordenadas de pantalla según la rotación actual, para que
        // funcione igual con el soporte en vertical o en horizontal
        val (sx, sy) = when (displayRotation) {
            Surface.ROTATION_90  -> -uy to ux
            Surface.ROTATION_180 -> -ux to -uy
            Surface.ROTATION_270 -> uy to -ux
            else                 -> ux to uy
        }

        // Móvil fuera del soporte (pantalla casi horizontal, boca abajo, en el
        // bolsillo…): la proyección es ~0 o apunta hacia abajo y atan2 se
        // dispara (-120°, +170° en el test del 24/09). No hay lean fiable.
        if (sqrt(sx * sx + sy * sy) < MIN_SCREEN_VERTICALITY || sy <= 0f) return null

        val lean = Math.toDegrees(atan2(-sx, sy).toDouble()).toFloat()
        return if (abs(lean) <= MAX_PLAUSIBLE_LEAN_DEG) lean else null
    }
}

package com.mototrack.utils

import android.content.Context

/**
 * La moto del usuario (nombre, marca, modelo y año) y la última calibración en parado, en las
 * mismas preferencias que el cero de inclinación que usa el servicio de grabación.
 *
 * Por ahora es una sola moto para toda la app, no una por cuenta: el cero de inclinación
 * también es uno solo, y depende del soporte y de cómo va el móvil colocado.
 */
object MotoProfile {
    const val PREFS = "mototrack_prefs"

    // Clave nueva: los offsets guardados con el cálculo antiguo (roll de getOrientation) no
    // valen para la inclinación lateral actual
    const val PREF_LEAN_OFFSET = "resting_lean_offset"
    // Nº de calibraciones fundidas en PREF_LEAN_OFFSET desde siempre (todas las rutas, no solo la
    // de hoy): con esto el offset es una media incremental, no el último valor suelto
    const val PREF_LEAN_COUNT = "resting_lean_offset_count"

    private const val KEY_NAME = "moto_name"
    private const val KEY_BRAND = "moto_brand"
    private const val KEY_MODEL = "moto_model"
    private const val KEY_YEAR = "moto_year"
    private const val KEY_MOUNT = "moto_phone_mount"
    private const val KEY_CALIBRATED_AT = "moto_calibrated_at"

    /**
     * Peso de una calibración en parado frente a las que hace el servicio rodando: cuenta como
     * este nº de calibraciones ya hechas, así que las siguientes de cada ruta solo la retocan
     * (la primera de una ruta pesa 1/([STOPPED_WEIGHT] + 1)). Se calibra a propósito con la moto
     * derecha, por eso vale más que una muestra suelta, pero no se toma como verdad absoluta.
     */
    const val STOPPED_WEIGHT = 5

    /** Dónde va el móvil en la moto: el mínimo que hay que saber para fiarse de la calibración. */
    val MOUNTS = listOf("Manillar", "Depósito", "Carenado / salpicadero", "Otro")

    /** [mount] es uno de [MOUNTS] o vacío si no se ha indicado. */
    data class Moto(
        val name: String, val brand: String, val model: String, val year: String, val mount: String
    ) {
        val isEmpty get() = name.isBlank() && brand.isBlank() && model.isBlank() && year.isBlank() && mount.isBlank()
    }

    data class Calibration(val offsetDeg: Float, val atMs: Long)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Moto = prefs(context).let {
        Moto(
            it.getString(KEY_NAME, "") ?: "", it.getString(KEY_BRAND, "") ?: "",
            it.getString(KEY_MODEL, "") ?: "", it.getString(KEY_YEAR, "") ?: "",
            it.getString(KEY_MOUNT, "") ?: ""
        )
    }

    fun save(context: Context, moto: Moto) {
        prefs(context).edit()
            .putString(KEY_NAME, moto.name.trim())
            .putString(KEY_BRAND, moto.brand.trim())
            .putString(KEY_MODEL, moto.model.trim())
            .putString(KEY_YEAR, moto.year.trim())
            .putString(KEY_MOUNT, moto.mount)
            .apply()
    }

    /** Cero de inclinación vigente (el que resta el servicio a cada lectura). */
    fun leanOffset(context: Context): Float = prefs(context).getFloat(PREF_LEAN_OFFSET, 0f)

    /** Si ya hay un cero de inclinación guardado (de una calibración en parado o del servicio). */
    fun hasLeanOffset(context: Context): Boolean = prefs(context).getInt(PREF_LEAN_COUNT, 0) > 0

    /** null si nunca se ha calibrado en parado. */
    fun lastCalibration(context: Context): Calibration? {
        val at = prefs(context).getLong(KEY_CALIBRATED_AT, 0L)
        return if (at == 0L) null else Calibration(leanOffset(context), at)
    }

    /** Guarda [offsetDeg] como cero de inclinación de la moto (ver [STOPPED_WEIGHT]). */
    fun applyCalibration(context: Context, offsetDeg: Float) {
        prefs(context).edit()
            .putFloat(PREF_LEAN_OFFSET, offsetDeg)
            .putInt(PREF_LEAN_COUNT, STOPPED_WEIGHT)
            .putLong(KEY_CALIBRATED_AT, System.currentTimeMillis())
            .apply()
    }
}

package com.mototrack.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Registro de depuración: escribe una fila CSV cada 100 ms (10 Hz) mientras se
 * graba una ruta, independiente de los puntos GPS (que llegan como mucho 1/s).
 * Así se ven los picos de inclinación/aceleración entre punto y punto.
 */
class SensorLogger(private val file: File) {

    private var job: Job? = null

    /** [sample] devuelve la fila ya formateada con el estado actual de los sensores. */
    fun start(scope: CoroutineScope, sample: () -> String) {
        file.parentFile?.mkdirs()
        val writer = file.bufferedWriter()
        writer.write(HEADER)
        writer.newLine()

        job = scope.launch(Dispatchers.IO) {
            var rows = 0
            try {
                while (isActive) {
                    writer.write(sample())
                    writer.newLine()
                    // Volcar a disco cada segundo: si la app muere, se pierde como mucho 1 s
                    if (++rows % 10 == 0) writer.flush()
                    delay(INTERVAL_MS)
                }
            } finally {
                // Se ejecuta también al cancelar (DETENER o servicio destruido)
                writer.close()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        const val INTERVAL_MS = 100L

        const val HEADER = "timestamp_ms,elapsed_s,lean_raw_deg,lean_offset_deg,lean_deg," +
            "screen_rotation,accel_x,accel_y,accel_z,accel_total,speed_kmh," +
            "lat,lon,accuracy_m,fix_age_s,source,speed_limit_kmh,long_accel_ms2," +
            "raw_ax,raw_ay,raw_az,rv_x,rv_y,rv_z,rv_w,rv_acc"

        fun fileFor(context: Context, routeId: Long) =
            File(context.filesDir, "sensor_logs/route_$routeId.csv")
    }
}

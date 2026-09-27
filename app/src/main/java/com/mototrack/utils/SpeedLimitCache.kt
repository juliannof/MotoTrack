package com.mototrack.utils

import com.mototrack.data.SpeedLimitCacheDao
import com.mototrack.data.SpeedLimitCacheEntry
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Caché local de límites (Room) delante de Overpass: pasar otra vez por una vía
 * ya consultada no gasta red ni espera al servidor, y el límite se actualiza
 * al instante al entrar en un tramo conocido.
 *
 * La clave es una celda de ~20 m más el eje de la marcha (4 sectores de 45°,
 * igual en ambos sentidos): así dos vías cruzadas o paralelas en la misma
 * celda no se pisan si van en direcciones distintas.
 */
object SpeedLimitCache {

    // Súbelo si cambian las reglas de elección/estimación: descarta lo guardado
    private const val VERSION = "v2"
    private const val CELL_DEG = 0.0002
    private const val TTL_KNOWN_MS = 60L * 24 * 3600 * 1000     // 60 días
    private const val TTL_UNKNOWN_MS = 2L * 24 * 3600 * 1000    // 2 días: OSM se completa

    private const val METERS_PER_DEG = 111_320.0
    // Al rellenar a lo largo de una vía: una celda cada ~8 m, hasta 400 m del punto consultado
    private const val FILL_STEP_M = 8.0
    private const val FILL_RADIUS_M = 400.0

    /** Eje de la marcha en 4 sectores de 45° (igual en ambos sentidos). */
    private fun axisBucket(bearing: Float) = (((bearing % 180f) / 45f).roundToInt() % 4)

    internal fun key(lat: Double, lon: Double, bucket: String): String {
        val row = floor(lat / CELL_DEG).toLong()
        val col = floor(lon / CELL_DEG).toLong()
        return "$VERSION:$row:$col:$bucket"
    }

    internal fun key(lat: Double, lon: Double, bearing: Float?) =
        key(lat, lon, if (bearing == null) "n" else axisBucket(bearing).toString())

    /**
     * Límite guardado para este punto y sentido. Si el rumbo cae en el borde entre dos
     * sectores, también se prueban los vecinos (±45°, nunca la vía que cruza a 90°).
     */
    suspend fun get(dao: SpeedLimitCacheDao, lat: Double, lon: Double, bearing: Float?): SpeedLimitProvider.Result.Ok? {
        val buckets = if (bearing == null) listOf("n") else {
            val b = axisBucket(bearing)
            listOf(b, (b + 1) % 4, (b + 3) % 4).map { it.toString() }
        }
        for (bucket in buckets) {
            val e = dao.get(key(lat, lon, bucket)) ?: continue
            val ttl = if (e.limitKmh > 0) TTL_KNOWN_MS else TTL_UNKNOWN_MS
            if (System.currentTimeMillis() - e.fetchedAt > ttl) continue
            return SpeedLimitProvider.Result.Ok(e.limitKmh.takeIf { it > 0 }, e.estimated)
        }
        return null
    }

    /**
     * Rellena la caché a lo largo del trazado de la vía (hasta 400 m alrededor del punto
     * consultado): al llegar a cualquier punto de ese tramo el límite ya está guardado y
     * no hay que esperar a la red. Cada celda se guarda con el eje de su propio segmento.
     */
    suspend fun putAlong(dao: SpeedLimitCacheDao, centerLat: Double, centerLon: Double, r: SpeedLimitProvider.Result.Ok) {
        val path = r.path ?: return
        val kx = cos(Math.toRadians(centerLat))
        val now = System.currentTimeMillis()
        val out = LinkedHashMap<String, SpeedLimitCacheEntry>()
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val dx = (b[1] - a[1]) * METERS_PER_DEG * kx
            val dy = (b[0] - a[0]) * METERS_PER_DEG
            val len = hypot(dx, dy)
            if (len < 0.5) continue
            val bucket = axisBucket(((Math.toDegrees(atan2(dx, dy)) + 360) % 360).toFloat()).toString()
            val steps = ceil(len / FILL_STEP_M).toInt()
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                val lat = a[0] + (b[0] - a[0]) * t
                val lon = a[1] + (b[1] - a[1]) * t
                val fromCenter = hypot((lon - centerLon) * METERS_PER_DEG * kx, (lat - centerLat) * METERS_PER_DEG)
                if (fromCenter > FILL_RADIUS_M) continue
                for (bk in listOf(bucket, "n")) {
                    val k = key(lat, lon, bk)
                    out.getOrPut(k) { SpeedLimitCacheEntry(k, r.limitKmh ?: 0, r.estimated, now) }
                }
            }
        }
        if (out.isNotEmpty()) dao.putAllIfAbsent(out.values.toList())
    }

    suspend fun put(dao: SpeedLimitCacheDao, lat: Double, lon: Double, bearing: Float?, r: SpeedLimitProvider.Result.Ok) {
        dao.put(SpeedLimitCacheEntry(key(lat, lon, bearing), r.limitKmh ?: 0, r.estimated, System.currentTimeMillis()))
    }
}

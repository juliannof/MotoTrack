package com.mototrack.utils

import com.mototrack.data.SpeedLimitCacheDao
import com.mototrack.data.SpeedLimitCacheEntry
import kotlin.math.floor
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

    internal fun key(lat: Double, lon: Double, bearing: Float?): String {
        val row = floor(lat / CELL_DEG).toLong()
        val col = floor(lon / CELL_DEG).toLong()
        val axis = if (bearing == null) "n"
        else (((bearing % 180f) / 45f).roundToInt() % 4).toString()
        return "$VERSION:$row:$col:$axis"
    }

    suspend fun get(dao: SpeedLimitCacheDao, lat: Double, lon: Double, bearing: Float?): SpeedLimitProvider.Result.Ok? {
        val e = dao.get(key(lat, lon, bearing)) ?: return null
        val ttl = if (e.limitKmh > 0) TTL_KNOWN_MS else TTL_UNKNOWN_MS
        if (System.currentTimeMillis() - e.fetchedAt > ttl) return null
        return SpeedLimitProvider.Result.Ok(e.limitKmh.takeIf { it > 0 }, e.estimated)
    }

    suspend fun put(dao: SpeedLimitCacheDao, lat: Double, lon: Double, bearing: Float?, r: SpeedLimitProvider.Result.Ok) {
        dao.put(SpeedLimitCacheEntry(key(lat, lon, bearing), r.limitKmh ?: 0, r.estimated, System.currentTimeMillis()))
    }
}

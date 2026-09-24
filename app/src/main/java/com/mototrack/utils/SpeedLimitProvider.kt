package com.mototrack.utils

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Límite de velocidad de la vía por la que vas, a partir de OpenStreetMap
 * (etiqueta `maxspeed`) consultado a la Overpass API. Necesita conexión de datos.
 *
 * Con la posición y el rumbo elegimos, de las vías cercanas con límite, la que
 * mejor encaja: la más próxima y orientada en la misma dirección que la marcha.
 */
object SpeedLimitProvider {

    private const val TAG = "SpeedLimit"
    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    private const val SEARCH_RADIUS_M = 30
    private const val METERS_PER_DEG = 111_320.0

    sealed interface Result {
        /**
         * Vía encontrada. limitKmh es null si no hay dato ni estimación;
         * estimated indica que sale del tipo de vía y no de un `maxspeed`.
         */
        data class Ok(val limitKmh: Int?, val estimated: Boolean = false) : Result
        /** Sin red / servidor ocupado: conviene conservar el último valor. */
        object Failed : Result
    }

    /** Bloquea mientras consulta la red: llamar desde Dispatchers.IO. */
    fun fetch(lat: Double, lon: Double, bearing: Float?): Result {
        val query = "[out:json][timeout:6];" +
            "way(around:$SEARCH_RADIUS_M,$lat,$lon)[highway];out tags geom;"
        return try {
            val conn = (URL("$ENDPOINT?data=${URLEncoder.encode(query, "UTF-8")}")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 7000
                setRequestProperty("User-Agent", "MotoTrack/1.0 (Android)")
            }
            try {
                if (conn.responseCode != 200) {
                    Log.w(TAG, "Overpass respondió ${conn.responseCode}")
                    return Result.Failed
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                pickLimit(JSONObject(body), lat, lon, bearing)
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Sin datos de límite: ${e.message}")
            Result.Failed
        } catch (e: Exception) {
            Log.w(TAG, "Respuesta no válida: ${e.message}")
            Result.Failed
        }
    }

    private fun pickLimit(json: JSONObject, lat: Double, lon: Double, bearing: Float?): Result.Ok {
        val elements = json.optJSONArray("elements") ?: return Result.Ok(null)
        val kx = cos(Math.toRadians(lat))
        var best: Result.Ok = Result.Ok(null)
        var bestScore = Double.MAX_VALUE

        for (i in 0 until elements.length()) {
            val way = elements.getJSONObject(i)
            val tags = way.optJSONObject("tags") ?: continue
            val explicit = parseMaxspeed(tags.optString("maxspeed"))
            val limit = explicit ?: estimateFromHighway(tags.optString("highway"))
            val geom = way.optJSONArray("geometry") ?: continue

            // Segmento más cercano a nuestra posición (plano local en metros)
            var minDist = Double.MAX_VALUE
            var segBearing = 0.0
            for (j in 0 until geom.length() - 1) {
                val a = geom.getJSONObject(j)
                val b = geom.getJSONObject(j + 1)
                val ax = (a.getDouble("lon") - lon) * METERS_PER_DEG * kx
                val ay = (a.getDouble("lat") - lat) * METERS_PER_DEG
                val bx = (b.getDouble("lon") - lon) * METERS_PER_DEG * kx
                val by = (b.getDouble("lat") - lat) * METERS_PER_DEG
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val d = hypot(ax + t * dx, ay + t * dy)
                if (d < minDist) {
                    minDist = d
                    segBearing = (Math.toDegrees(atan2(dx, dy)) + 360) % 360
                }
            }

            // Las vías son de doble sentido: vale el rumbo igual o el opuesto
            var score = minDist
            if (explicit == null) score += 15   // ante empate, gana la vía con dato real
            if (bearing != null) {
                val diff = abs(((bearing - segBearing + 540) % 360) - 180)  // 0..180
                val offAxis = minOf(diff, 180 - diff)                       // 0..90
                if (offAxis > 40) score += 50
            }
            if (score < bestScore) {
                bestScore = score
                best = Result.Ok(limit, estimated = explicit == null && limit != null)
            }
        }
        return best
    }

    /**
     * Límite genérico en España según el tipo de vía, cuando OSM no trae `maxspeed`.
     * Es una estimación: por eso la señal se muestra distinta.
     */
    internal fun estimateFromHighway(highway: String?): Int? = when (highway) {
        "motorway", "motorway_link" -> 120
        "trunk" -> 100
        "primary", "secondary", "tertiary" -> 90
        "residential", "living_street" -> 30
        else -> null
    }

    /** "50" → 50, "30 mph" → 48, "ES:urban" → 50… Null si no se puede saber. */
    internal fun parseMaxspeed(raw: String?): Int? {
        val v = raw?.trim()?.lowercase() ?: return null
        v.toIntOrNull()?.let { return it }
        Regex("""^(\d+)\s*mph$""").find(v)?.let { return (it.groupValues[1].toInt() * 1.609).toInt() }
        return when {
            v.endsWith(":zone30") -> 30
            v.endsWith(":urban") -> 50
            v.endsWith(":motorway") -> 120
            v.endsWith(":trunk") -> 90
            v.endsWith(":rural") -> 90
            else -> null   // "none", "signals", "walk"…
        }
    }
}

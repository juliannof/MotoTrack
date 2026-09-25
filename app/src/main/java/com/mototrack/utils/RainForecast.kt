package com.mototrack.utils

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * ¿Va a llover donde estás? Previsión de las próximas horas de Open-Meteo (gratuita y sin
 * clave). Necesita conexión de datos.
 */
object RainForecast {

    private const val TAG = "RainForecast"
    private const val HOURS = 4              // esta hora y las 3 siguientes
    private const val RAIN_PROB_PCT = 50     // desde aquí, la hora cuenta como "lluvia"
    private const val RAIN_MM = 0.1          // o si se esperan al menos estos mm

    sealed interface Result {
        /**
         * [raining] llueve ahora; [hoursToRain] horas hasta la primera con lluvia prevista
         * (0 = esta hora), null si no hay ninguna; [maxProbPct] la probabilidad más alta del periodo.
         */
        data class Ok(val raining: Boolean, val hoursToRain: Int?, val maxProbPct: Int) : Result
        object Failed : Result
    }

    /** Bloquea mientras consulta la red: llamar desde Dispatchers.IO. */
    fun fetch(lat: Double, lon: Double): Result {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=precipitation&hourly=precipitation_probability,precipitation" +
                "&forecast_hours=%d&timezone=auto", lat, lon, HOURS
        )
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 7000
                setRequestProperty("User-Agent", "MotoTrack/1.0 (Android)")
            }
            try {
                if (conn.responseCode != 200) {
                    Log.w(TAG, "Open-Meteo respondió ${conn.responseCode}")
                    return Result.Failed
                }
                parse(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Sin previsión: ${e.message}")
            Result.Failed
        } catch (e: Exception) {
            Log.w(TAG, "Respuesta no válida: ${e.message}")
            Result.Failed
        }
    }

    private fun parse(json: JSONObject): Result.Ok {
        val raining = json.optJSONObject("current")?.optDouble("precipitation", 0.0)?.let { it >= RAIN_MM } ?: false
        val hourly = json.getJSONObject("hourly")
        val prob = hourly.getJSONArray("precipitation_probability")
        val mm = hourly.getJSONArray("precipitation")
        var first: Int? = null
        var maxProb = 0
        for (i in 0 until prob.length()) {
            val p = prob.optInt(i, 0)
            maxProb = maxOf(maxProb, p)
            if (first == null && (p >= RAIN_PROB_PCT || mm.optDouble(i, 0.0) >= RAIN_MM)) first = i
        }
        return Result.Ok(raining, first, maxProb)
    }
}

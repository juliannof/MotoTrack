package com.mototrack.utils

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.util.Log
import com.mototrack.data.RoutePoint
import java.io.IOException
import java.util.Locale

/**
 * Pone a la ruta un nombre legible a partir de los lugares por los que pasa:
 * "De Marbella a Ojén por Mijas".
 *
 * Usa el Geocoder de Android (dirección a partir de coordenadas), que
 * necesita conexión de datos. Sin red devuelve null y la ruta conserva
 * su nombre.
 */
object RouteNamer {

    private const val TAG = "RouteNamer"

    // Nombre que propone el diálogo de "Nueva Ruta": solo ese se sustituye
    private val DEFAULT_NAME = Regex("""^Ruta \d{2}/\d{2}/\d{4} \d{2}:\d{2}$""")

    fun isDefaultName(name: String) = DEFAULT_NAME.matches(name)

    /**
     * Lugares propios: si un punto cae dentro del radio, se usa este nombre en
     * lugar del barrio o municipio del Geocoder (p. ej. "Ojén" → el colegio).
     */
    private class KnownPlace(val name: String, val lat: Double, val lon: Double, val radiusM: Float)

    private val KNOWN_PLACES = listOf(
        // Ojén: el aparcamiento está enfrente, a unos 26 m
        KnownPlace("Colegio Alemán de Málaga", 36.52976, -4.75388, 150f)
    )

    private fun knownPlaceAt(lat: Double, lon: Double): String? {
        val out = FloatArray(1)
        return KNOWN_PLACES
            .map { it to run { Location.distanceBetween(lat, lon, it.lat, it.lon, out); out[0] } }
            .filter { (place, dist) -> dist <= place.radiusM }
            .minByOrNull { (_, dist) -> dist }
            ?.first?.name
    }

    /**
     * Bloquea mientras consulta la red: llamar desde Dispatchers.IO.
     * Devuelve null si no hay geocoder, conexión o puntos válidos.
     */
    fun describe(context: Context, points: List<RoutePoint>): String? {
        if (!Geocoder.isPresent()) return null
        val valid = points.filter { it.latitude != 0.0 || it.longitude != 0.0 }
        if (valid.isEmpty()) return null

        val geocoder = Geocoder(context, Locale.getDefault())
        fun placeAt(p: RoutePoint): String? = knownPlaceAt(p.latitude, p.longitude) ?: try {
            // La versión con callback es de API 33; esta síncrona vale para
            // minSdk 26 y ya estamos en un hilo de fondo
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(p.latitude, p.longitude, 1)?.firstOrNull()?.placeName()
        } catch (e: IOException) {
            Log.w(TAG, "Geocoder sin respuesta: ${e.message}")
            null
        }

        val from = placeAt(valid.first()) ?: return null
        val to = placeAt(valid.last()) ?: return null

        // "por …": el primer lugar intermedio distinto del origen y el destino
        val via = listOf(0.5, 0.25, 0.75)
            .map { valid[(it * (valid.size - 1)).toInt()] }
            .asSequence()
            .mapNotNull(::placeAt)
            .firstOrNull { it != from && it != to }

        return when {
            from != to && via != null -> "De $from a $to por $via"
            from != to -> "De $from a $to"
            via != null -> "Vuelta desde $from por $via"
            else -> "Ruta por $from"
        }
    }

    /** Barrio o urbanización si existe (p. ej. "Las Lagunas de Mijas"); si no, el municipio. */
    private fun Address.placeName(): String? = subLocality ?: locality ?: subAdminArea
}

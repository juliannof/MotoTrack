package com.mototrack.utils

import android.content.Context
import android.location.Location
import android.location.OnNmeaMessageListener

/**
 * Altura sobre el nivel del mar. Location.altitude es la altura sobre el elipsoide
 * GPS (en Málaga, ~50 m más). El GPS informa de la ondulación del geoide en las
 * sentencias NMEA GGA; restándola sale la altura sobre el nivel del mar.
 *
 * La ondulación cambia muy despacio con la posición, así que se comparte entre
 * todas las instancias y se guarda: al abrir la app (o girar la pantalla) ya hay
 * un valor bueno y la altura no salta 50 m mientras llega la primera sentencia.
 *
 * Registrar [nmeaListener] en el LocationManager mientras se use.
 */
class MslAltitude(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("mototrack_prefs", Context.MODE_PRIVATE)

    init {
        if (geoidSeparationM == null) {
            prefs.getFloat(KEY_GEOID, Float.NaN).takeIf { !it.isNaN() }
                ?.let { geoidSeparationM = it.toDouble() }
        }
    }

    val nmeaListener = OnNmeaMessageListener { message, _ ->
        // $GPGGA,hora,lat,N,lon,E,calidad,sats,hdop,altMSL,M,ondulación,M,...
        if (message.length > 6 && message[0] == '$' && message.substring(3, 6) == "GGA") {
            message.split(',').getOrNull(11)?.toDoubleOrNull()?.let { sep ->
                val changed = geoidSeparationM?.let { kotlin.math.abs(it - sep) > 0.5 } ?: true
                geoidSeparationM = sep
                if (changed) prefs.edit().putFloat(KEY_GEOID, sep.toFloat()).apply()
            }
        }
    }

    /** Altura sobre el mar, o null si aún no se conoce el geoide (mejor nada que 50 m mal). */
    fun ofOrNull(location: Location): Double? = when {
        location.hasMslAltitude() -> location.mslAltitudeMeters
        else -> geoidSeparationM?.let { location.altitude - it }
    }

    /** Para grabar: sin dato del geoide se guarda la del elipsoide. */
    fun of(location: Location): Double = ofOrNull(location) ?: location.altitude

    private companion object {
        const val KEY_GEOID = "geoid_separation_m"
        @Volatile var geoidSeparationM: Double? = null
    }
}

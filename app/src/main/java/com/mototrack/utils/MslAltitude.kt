package com.mototrack.utils

import android.location.Location
import android.location.OnNmeaMessageListener

/**
 * Altura sobre el nivel del mar. Location.altitude es la altura sobre el elipsoide
 * GPS (en Málaga, ~50 m más). El GPS informa de la ondulación del geoide en las
 * sentencias NMEA GGA; restándola sale la altura sobre el nivel del mar.
 *
 * Registrar [nmeaListener] en el LocationManager mientras se use.
 */
class MslAltitude {

    @Volatile private var geoidSeparationM: Double? = null

    val nmeaListener = OnNmeaMessageListener { message, _ ->
        // $GPGGA,hora,lat,N,lon,E,calidad,sats,hdop,altMSL,M,ondulación,M,...
        if (message.length > 6 && message[0] == '$' && message.substring(3, 6) == "GGA") {
            message.split(',').getOrNull(11)?.toDoubleOrNull()?.let { geoidSeparationM = it }
        }
    }

    /** Si Android ya da la altura sobre el mar, esa; si no, la del elipsoide menos el geoide. */
    fun of(location: Location): Double = when {
        location.hasMslAltitude() -> location.mslAltitudeMeters
        else -> geoidSeparationM?.let { location.altitude - it } ?: location.altitude
    }
}

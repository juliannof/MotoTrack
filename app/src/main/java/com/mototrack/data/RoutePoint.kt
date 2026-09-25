package com.mototrack.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Representa un punto individual en una ruta grabada.
 * Almacena GPS, velocidad, aceleración y ángulo de inclinación.
 */
@Entity(
    tableName = "route_points",
    foreignKeys = [ForeignKey(
        entity = Route::class,
        parentColumns = ["id"],
        childColumns = ["routeId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("routeId")]
)
data class RoutePoint(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val routeId: Long,

    // Timestamp en milisegundos
    val timestamp: Long,

    // GPS
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,       // metros sobre nivel del mar
    val accuracy: Float,        // precisión GPS en metros

    // Velocidad (km/h) — derivada del GPS
    val speedKmh: Float,

    // Aceleración (m/s²) de los 3 ejes
    val accelX: Float,          // lateral
    val accelY: Float,          // adelante/atrás
    val accelZ: Float,          // vertical
    val accelTotal: Float,      // magnitud resultante (sin gravedad)

    // Ángulo de inclinación lateral (lean angle) en grados
    val leanAngle: Float,

    // Orientación (azimuth) en grados
    val bearing: Float,
    val hdop: Float,              // precisión horizontal GPS (menor = mejor)
    val vdop: Float,              // precisión vertical
    val satellites: Int,          // satélites usados
    val altitudeEllipsoid: Double, // altitud sobre el elipsoide (vs nivel del mar)

    // Límite de velocidad de la vía (OpenStreetMap), en km/h; 0 = desconocido
    val speedLimitKmh: Int = 0,
    // true si el límite se dedujo del tipo de vía y no de una etiqueta maxspeed
    val speedLimitEstimated: Boolean = false,

    // Aceleración en el sentido de la marcha (m/s²): + acelerando, - frenando
    val longAccel: Float = 0f
)

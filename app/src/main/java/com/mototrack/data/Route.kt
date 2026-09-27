package com.mototrack.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entidad que representa una ruta completa grabada.
 */
@Entity(tableName = "routes")
data class Route(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    // Correo de la cuenta dueña de la ruta ("" = grabada antes de existir las cuentas)
    val ownerEmail: String = "",
    val startTime: Long,
    val endTime: Long = 0L,

    // Estadísticas generales
    val distanceKm: Float = 0f,
    val maxSpeedKmh: Float = 0f,
    val avgSpeedKmh: Float = 0f,
    val maxLeanAngle: Float = 0f,   // máximo de ambos lados
    val maxLeanLeft: Float = 0f,    // grados, valor positivo
    val maxLeanRight: Float = 0f,   // grados, valor positivo
    val maxAcceleration: Float = 0f,

    // Estado
    val isCompleted: Boolean = false,

    // Notas del usuario
    val notes: String = ""
)

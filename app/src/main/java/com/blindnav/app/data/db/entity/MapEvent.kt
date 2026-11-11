package com.blindnav.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * EventType - Tipos de eventos del mapa (estilo Waze)
 */
enum class EventType {
    /** Paso de cebra / cruce peatonal */
    CROSSING,
    
    /** Obstáculo permanente (ej: poste, banco) */
    OBSTACLE_PERMANENT,
    
    /** Obstáculo temporal (ej: obras, coche mal aparcado) */
    OBSTACLE_TEMPORARY,
    
    /** Giro o cambio de dirección */
    TURN,
    
    /** Información general / nota de voz */
    INFO;
    
    /** Nombre para mostrar en español */
    val displayName: String
        get() = when (this) {
            CROSSING -> "Paso de cebra"
            OBSTACLE_PERMANENT -> "Obstáculo permanente"
            OBSTACLE_TEMPORARY -> "Obstáculo temporal"
            TURN -> "Giro"
            INFO -> "Nota"
        }
    
    /** Emoji para el botón */
    val emoji: String
        get() = when (this) {
            CROSSING -> "🦓"
            OBSTACLE_PERMANENT -> "🚧"
            OBSTACLE_TEMPORARY -> "⚠️"
            TURN -> "🛑"
            INFO -> "🎤"
        }
}

/**
 * MapEvent - Evento reportado en el mapa (estilo Waze)
 * 
 * Representa un punto de interés o riesgo:
 * - Paso de cebra (con orientación de cruce)
 * - Obstáculo permanente o temporal
 * - Giro importante
 * - Nota de voz / información
 * 
 * Incluye la orientación de brújula (bearing) para saber
 * hacia dónde miraba el usuario al reportarlo.
 */
@Entity(
    tableName = "map_events",
    foreignKeys = [
        ForeignKey(
            entity = Route::class,
            parentColumns = ["id"],
            childColumns = ["routeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["routeId"]), Index(value = ["type"])]
)
data class MapEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** ID de la ruta a la que pertenece */
    val routeId: Long,
    
    /** Tipo de evento */
    val type: EventType,
    
    /** Latitud GPS */
    val latitude: Double,
    
    /** Longitud GPS */
    val longitude: Double,
    
    /** Descripción del evento (ej: "Zanja en la acera") */
    val description: String,
    
    /** Dirección de la brújula al reportarlo (0-360, donde 0=Norte) */
    val bearing: Float,
    
    /** Precisión del GPS en metros */
    val gpsAccuracy: Float,
    
    /** Orden en la ruta (0, 1, 2...) */
    val orderIndex: Int,
    
    /** Timestamp de creación */
    val createdAt: Long = System.currentTimeMillis(),
    /** Rumbo del móvil confirmado para el tramo de salida; null en rutas antiguas. */
    val targetBearing: Float? = null,
    /** Giro firmado medido entre el inicio y el final: positivo hacia la derecha. */
    val turnDegrees: Float? = null,
    /** Button-recorded manoeuvre with an explicit start and finish. */
    @androidx.room.ColumnInfo(defaultValue = "0")
    val hasExplicitEnd: Boolean = false,
    val endLatitude: Double? = null,
    val endLongitude: Double? = null,
    val endedAt: Long? = null
)

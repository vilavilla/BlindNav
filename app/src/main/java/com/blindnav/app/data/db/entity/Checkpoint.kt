package com.blindnav.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Checkpoint - Punto de control de navegación
 * 
 * Representa un punto clave en una ruta:
 * - Paso de cebra
 * - Esquina
 * - Entrada de edificio
 * - Punto de referencia
 */
@Entity(
    tableName = "checkpoints",
    foreignKeys = [
        ForeignKey(
            entity = Route::class,
            parentColumns = ["id"],
            childColumns = ["routeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["routeId"])]
)
data class Checkpoint(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** ID de la ruta a la que pertenece */
    val routeId: Long,
    
    /** Latitud GPS */
    val latitude: Double,
    
    /** Longitud GPS */
    val longitude: Double,
    
    /** Descripción del punto (ej: "Paso de cebra", "Esquina izquierda") */
    val description: String,
    
    /** Orden en la ruta (0, 1, 2...) */
    val orderIndex: Int,
    
    /** Tipo de checkpoint */
    val type: CheckpointType = CheckpointType.WAYPOINT,
    
    /** Timestamp de creación */
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Tipos de checkpoint para feedback especializado
 */
enum class CheckpointType {
    /** Punto de paso genérico */
    WAYPOINT,
    
    /** Paso de cebra - activa crossing assist */
    ZEBRA_CROSSING,
    
    /** Esquina o giro */
    CORNER,
    
    /** Entrada de edificio */
    ENTRANCE,
    
    /** Escaleras */
    STAIRS,
    
    /** Parada de transporte */
    TRANSIT_STOP
}

package com.blindnav.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * PathPoint - Punto de trazado de ruta
 * 
 * Se graba automáticamente cada 5 metros para dibujar
 * la línea de la ruta en el mapa (breadcrumb trail).
 */
@Entity(
    tableName = "path_points",
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
data class PathPoint(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** ID de la ruta a la que pertenece */
    val routeId: Long,
    
    /** Latitud GPS */
    val latitude: Double,
    
    /** Longitud GPS */
    val longitude: Double,
    
    /** Timestamp de captura */
    val timestamp: Long = System.currentTimeMillis()
)

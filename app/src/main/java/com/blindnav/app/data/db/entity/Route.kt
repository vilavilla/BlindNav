package com.blindnav.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Route - Entidad de ruta guardada
 * 
 * Representa una ruta personalizada creada por el usuario
 * que contiene una lista de checkpoints.
 */
@Entity(tableName = "routes")
data class Route(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** Nombre descriptivo de la ruta */
    val name: String,
    
    /** Descripción opcional de la ruta */
    val description: String = "",
    
    /** Timestamp de creación */
    val createdAt: Long = System.currentTimeMillis(),
    
    /** Indica si es la ruta activa actualmente */
    val isActive: Boolean = false
)

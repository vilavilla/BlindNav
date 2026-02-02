package com.blindnav.app.data.db.dao

import androidx.room.*
import com.blindnav.app.data.db.entity.EventType
import com.blindnav.app.data.db.entity.MapEvent
import kotlinx.coroutines.flow.Flow

/**
 * DAO para operaciones con eventos del mapa (estilo Waze).
 */
@Dao
interface MapEventDao {
    
    /**
     * Inserta un nuevo evento.
     * @return ID del evento insertado
     */
    @Insert
    suspend fun insert(event: MapEvent): Long
    
    /**
     * Inserta múltiples eventos.
     */
    @Insert
    suspend fun insertAll(events: List<MapEvent>)
    
    /**
     * Actualiza un evento existente.
     */
    @Update
    suspend fun update(event: MapEvent)
    
    /**
     * Elimina un evento.
     */
    @Delete
    suspend fun delete(event: MapEvent)
    
    /**
     * Obtiene todos los eventos de una ruta ordenados.
     */
    @Query("SELECT * FROM map_events WHERE routeId = :routeId ORDER BY orderIndex ASC")
    fun getEventsByRoute(routeId: Long): Flow<List<MapEvent>>
    
    /**
     * Obtiene eventos por tipo en una ruta.
     */
    @Query("SELECT * FROM map_events WHERE routeId = :routeId AND type = :type ORDER BY orderIndex ASC")
    fun getEventsByType(routeId: Long, type: EventType): Flow<List<MapEvent>>
    
    /**
     * Obtiene un evento por ID.
     */
    @Query("SELECT * FROM map_events WHERE id = :eventId")
    suspend fun getEventById(eventId: Long): MapEvent?
    
    /**
     * Obtiene el siguiente evento en una ruta.
     */
    @Query("""
        SELECT * FROM map_events 
        WHERE routeId = :routeId AND orderIndex > :currentIndex 
        ORDER BY orderIndex ASC 
        LIMIT 1
    """)
    suspend fun getNextEvent(routeId: Long, currentIndex: Int): MapEvent?
    
    /**
     * Cuenta los eventos de una ruta.
     */
    @Query("SELECT COUNT(*) FROM map_events WHERE routeId = :routeId")
    suspend fun getEventCount(routeId: Long): Int
    
    /**
     * Obtiene el último índice de orden de una ruta.
     */
    @Query("SELECT MAX(orderIndex) FROM map_events WHERE routeId = :routeId")
    suspend fun getMaxOrderIndex(routeId: Long): Int?
    
    /**
     * Elimina todos los eventos de una ruta.
     */
    @Query("DELETE FROM map_events WHERE routeId = :routeId")
    suspend fun deleteAllByRoute(routeId: Long)
    
    /**
     * Obtiene eventos cercanos a una ubicación (para alertas de proximidad).
     * Usa aproximación de grados a metros (0.00001° ≈ 1.1m).
     */
    @Query("""
        SELECT * FROM map_events 
        WHERE routeId = :routeId 
        AND ABS(latitude - :lat) < :radiusDegrees 
        AND ABS(longitude - :lon) < :radiusDegrees
        ORDER BY orderIndex ASC
    """)
    suspend fun getNearbyEvents(
        routeId: Long, 
        lat: Double, 
        lon: Double, 
        radiusDegrees: Double
    ): List<MapEvent>
}

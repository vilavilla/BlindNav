package com.blindnav.app.data.db.dao

import androidx.room.*
import com.blindnav.app.data.db.entity.PathPoint
import kotlinx.coroutines.flow.Flow

/**
 * DAO para operaciones con puntos de trazado (trail).
 */
@Dao
interface PathPointDao {
    
    /**
     * Inserta un nuevo punto de trazado.
     * @return ID del punto insertado
     */
    @Insert
    suspend fun insert(point: PathPoint): Long
    
    /**
     * Inserta múltiples puntos de trazado.
     */
    @Insert
    suspend fun insertAll(points: List<PathPoint>)
    
    /**
     * Obtiene todos los puntos de una ruta ordenados por tiempo.
     */
    @Query("SELECT * FROM path_points WHERE routeId = :routeId ORDER BY timestamp ASC")
    fun getPointsByRoute(routeId: Long): Flow<List<PathPoint>>
    
    /**
     * Obtiene el último punto de una ruta.
     */
    @Query("SELECT * FROM path_points WHERE routeId = :routeId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastPoint(routeId: Long): PathPoint?
    
    /**
     * Cuenta los puntos de una ruta.
     */
    @Query("SELECT COUNT(*) FROM path_points WHERE routeId = :routeId")
    suspend fun getPointCount(routeId: Long): Int
    
    /**
     * Elimina todos los puntos de una ruta.
     */
    @Query("DELETE FROM path_points WHERE routeId = :routeId")
    suspend fun deleteAllByRoute(routeId: Long)
}

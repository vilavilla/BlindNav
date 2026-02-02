package com.blindnav.app.data.db.dao

import androidx.room.*
import com.blindnav.app.data.db.entity.Checkpoint
import kotlinx.coroutines.flow.Flow

/**
 * DAO para operaciones con checkpoints.
 */
@Dao
interface CheckpointDao {
    
    /**
     * Inserta un nuevo checkpoint.
     * @return ID del checkpoint insertado
     */
    @Insert
    suspend fun insert(checkpoint: Checkpoint): Long
    
    /**
     * Inserta múltiples checkpoints.
     */
    @Insert
    suspend fun insertAll(checkpoints: List<Checkpoint>)
    
    /**
     * Actualiza un checkpoint existente.
     */
    @Update
    suspend fun update(checkpoint: Checkpoint)
    
    /**
     * Elimina un checkpoint.
     */
    @Delete
    suspend fun delete(checkpoint: Checkpoint)
    
    /**
     * Obtiene todos los checkpoints de una ruta ordenados.
     */
    @Query("SELECT * FROM checkpoints WHERE routeId = :routeId ORDER BY orderIndex ASC")
    fun getCheckpointsByRoute(routeId: Long): Flow<List<Checkpoint>>
    
    /**
     * Obtiene un checkpoint por ID.
     */
    @Query("SELECT * FROM checkpoints WHERE id = :checkpointId")
    suspend fun getCheckpointById(checkpointId: Long): Checkpoint?
    
    /**
     * Obtiene el siguiente checkpoint en una ruta.
     */
    @Query("""
        SELECT * FROM checkpoints 
        WHERE routeId = :routeId AND orderIndex > :currentIndex 
        ORDER BY orderIndex ASC 
        LIMIT 1
    """)
    suspend fun getNextCheckpoint(routeId: Long, currentIndex: Int): Checkpoint?
    
    /**
     * Cuenta los checkpoints de una ruta.
     */
    @Query("SELECT COUNT(*) FROM checkpoints WHERE routeId = :routeId")
    suspend fun getCheckpointCount(routeId: Long): Int
    
    /**
     * Obtiene el último índice de orden de una ruta.
     */
    @Query("SELECT MAX(orderIndex) FROM checkpoints WHERE routeId = :routeId")
    suspend fun getMaxOrderIndex(routeId: Long): Int?
    
    /**
     * Elimina todos los checkpoints de una ruta.
     */
    @Query("DELETE FROM checkpoints WHERE routeId = :routeId")
    suspend fun deleteAllByRoute(routeId: Long)
}

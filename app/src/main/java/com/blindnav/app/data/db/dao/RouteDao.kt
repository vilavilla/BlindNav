package com.blindnav.app.data.db.dao

import androidx.room.*
import com.blindnav.app.data.db.entity.Route
import kotlinx.coroutines.flow.Flow

/**
 * DAO para operaciones con rutas.
 */
@Dao
interface RouteDao {
    
    /**
     * Inserta una nueva ruta.
     * @return ID de la ruta insertada
     */
    @Insert
    suspend fun insert(route: Route): Long
    
    /**
     * Actualiza una ruta existente.
     */
    @Update
    suspend fun update(route: Route)
    
    /**
     * Elimina una ruta (en cascada elimina sus checkpoints).
     */
    @Delete
    suspend fun delete(route: Route)
    
    /**
     * Obtiene todas las rutas ordenadas por fecha de creación.
     */
    @Query("SELECT * FROM routes ORDER BY createdAt DESC")
    fun getAllRoutes(): Flow<List<Route>>
    
    /**
     * Obtiene una ruta por ID.
     */
    @Query("SELECT * FROM routes WHERE id = :routeId")
    suspend fun getRouteById(routeId: Long): Route?
    
    /**
     * Obtiene la ruta activa actual.
     */
    @Query("SELECT * FROM routes WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveRoute(): Route?
    
    /**
     * Desactiva todas las rutas.
     */
    @Query("UPDATE routes SET isActive = 0")
    suspend fun deactivateAllRoutes()
    
    /**
     * Activa una ruta específica (desactiva el resto).
     */
    @Transaction
    suspend fun setActiveRoute(routeId: Long) {
        deactivateAllRoutes()
        getRouteById(routeId)?.let { route ->
            update(route.copy(isActive = true))
        }
    }
}

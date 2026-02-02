package com.blindnav.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.blindnav.app.data.db.dao.CheckpointDao
import com.blindnav.app.data.db.dao.MapEventDao
import com.blindnav.app.data.db.dao.PathPointDao
import com.blindnav.app.data.db.dao.RouteDao
import com.blindnav.app.data.db.entity.Checkpoint
import com.blindnav.app.data.db.entity.MapEvent
import com.blindnav.app.data.db.entity.PathPoint
import com.blindnav.app.data.db.entity.Route

/**
 * BlindNavDatabase - Base de datos Room para navegación
 * 
 * v2: Añade PathPoint (trazado) y MapEvent (eventos estilo Waze)
 * 
 * Almacena rutas personalizadas, trazados y eventos
 * para navegación guiada con alertas de riesgo.
 */
@Database(
    entities = [Route::class, Checkpoint::class, PathPoint::class, MapEvent::class],
    version = 2,
    exportSchema = false
)
abstract class BlindNavDatabase : RoomDatabase() {
    
    abstract fun routeDao(): RouteDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun pathPointDao(): PathPointDao
    abstract fun mapEventDao(): MapEventDao
    
    companion object {
        @Volatile
        private var INSTANCE: BlindNavDatabase? = null
        
        /**
         * Obtiene la instancia singleton de la base de datos.
         */
        fun getInstance(context: Context): BlindNavDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BlindNavDatabase::class.java,
                    "blindnav_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

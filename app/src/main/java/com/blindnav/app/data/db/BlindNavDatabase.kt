package com.blindnav.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 4,
    exportSchema = false
)
abstract class BlindNavDatabase : RoomDatabase() {
    
    abstract fun routeDao(): RouteDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun pathPointDao(): PathPointDao
    abstract fun mapEventDao(): MapEventDao
    
    companion object {
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE map_events ADD COLUMN hasExplicitEnd INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE map_events ADD COLUMN endLatitude REAL")
                db.execSQL("ALTER TABLE map_events ADD COLUMN endLongitude REAL")
                db.execSQL("ALTER TABLE map_events ADD COLUMN endedAt INTEGER")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE map_events ADD COLUMN targetBearing REAL")
                db.execSQL("ALTER TABLE map_events ADD COLUMN turnDegrees REAL")
            }
        }
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
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

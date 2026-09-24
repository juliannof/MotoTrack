package com.mototrack.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Route::class, RoutePoint::class],
    version = 3,  // era 2
    exportSchema = false
)
abstract class MotoTrackDatabase : RoomDatabase() {

    abstract fun routeDao(): RouteDao

    companion object {
        /**
         * v2 -> v3: inclinación máxima por separado a izquierda y derecha.
         * Las rutas antiguas se rellenan desde route_points (aprox.: 1 punto/s y
         * ignorando lecturas >70°, que eran basura de orientación).
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routes ADD COLUMN maxLeanLeft REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE routes ADD COLUMN maxLeanRight REAL NOT NULL DEFAULT 0")
                db.execSQL("""UPDATE routes SET
                    maxLeanLeft = COALESCE((SELECT MAX(-leanAngle) FROM route_points
                        WHERE routeId = routes.id AND leanAngle < 0 AND leanAngle >= -70), 0),
                    maxLeanRight = COALESCE((SELECT MAX(leanAngle) FROM route_points
                        WHERE routeId = routes.id AND leanAngle > 0 AND leanAngle <= 70), 0)""")
                db.execSQL("UPDATE routes SET maxLeanAngle = MAX(maxLeanLeft, maxLeanRight)")
            }
        }

        @Volatile
        private var INSTANCE: MotoTrackDatabase? = null

        fun getDatabase(context: Context): MotoTrackDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MotoTrackDatabase::class.java,
                    "mototrack_database"
                ).addMigrations(MIGRATION_2_3).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

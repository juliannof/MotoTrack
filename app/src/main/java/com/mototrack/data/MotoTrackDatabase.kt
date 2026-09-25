package com.mototrack.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Route::class, RoutePoint::class, SpeedLimitCacheEntry::class],
    version = 6,  // era 5
    exportSchema = false
)
abstract class MotoTrackDatabase : RoomDatabase() {

    abstract fun routeDao(): RouteDao
    abstract fun speedLimitCacheDao(): SpeedLimitCacheDao

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

        /** v3 -> v4: límite de velocidad de la vía en cada punto. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE route_points ADD COLUMN speedLimitKmh INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE route_points ADD COLUMN speedLimitEstimated INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v4 -> v5: cada ruta pertenece a una cuenta. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routes ADD COLUMN ownerEmail TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v5 -> v6: caché local de límites de velocidad. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `speed_limit_cache` (`cellKey` TEXT NOT NULL, " +
                    "`limitKmh` INTEGER NOT NULL, `estimated` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`cellKey`))")
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
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

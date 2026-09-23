package com.mototrack.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Route::class, RoutePoint::class],
    version = 2,  // era 1
    exportSchema = false
)
abstract class MotoTrackDatabase : RoomDatabase() {

    abstract fun routeDao(): RouteDao

    companion object {
        @Volatile
        private var INSTANCE: MotoTrackDatabase? = null

        fun getDatabase(context: Context): MotoTrackDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MotoTrackDatabase::class.java,
                    "mototrack_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

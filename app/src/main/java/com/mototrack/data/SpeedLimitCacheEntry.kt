package com.mototrack.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Límite de velocidad ya consultado a Overpass para una celda de ~20 m y un sentido de marcha. */
@Entity(tableName = "speed_limit_cache")
data class SpeedLimitCacheEntry(
    @PrimaryKey val cellKey: String,
    val limitKmh: Int,          // 0 = la vía no tiene dato
    val estimated: Boolean,
    val fetchedAt: Long
)

@Dao
interface SpeedLimitCacheDao {
    @Query("SELECT * FROM speed_limit_cache WHERE cellKey = :key")
    suspend fun get(key: String): SpeedLimitCacheEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: SpeedLimitCacheEntry)
}

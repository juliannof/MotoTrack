package com.mototrack.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface RouteDao {

    // ── Rutas ─────────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertRoute(route: Route): Long

    @Update
    suspend fun updateRoute(route: Route)

    @Delete
    suspend fun deleteRoute(route: Route)

    @Query("SELECT * FROM routes ORDER BY startTime DESC")
    fun getAllRoutes(): LiveData<List<Route>>

    @Query("SELECT * FROM routes WHERE id = :id")
    suspend fun getRouteById(id: Long): Route?

    @Query("SELECT * FROM routes WHERE isCompleted = 0 LIMIT 1")
    suspend fun getActiveRoute(): Route?

    // ── Puntos ────────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertPoint(point: RoutePoint): Long

    @Query("SELECT * FROM route_points WHERE routeId = :routeId ORDER BY timestamp ASC")
    suspend fun getPointsForRoute(routeId: Long): List<RoutePoint>

    @Query("SELECT * FROM route_points WHERE routeId = :routeId ORDER BY timestamp ASC")
    fun getPointsForRouteLive(routeId: Long): LiveData<List<RoutePoint>>

    @Query("SELECT COUNT(*) FROM route_points WHERE routeId = :routeId")
    suspend fun countPoints(routeId: Long): Int

    @Query("SELECT * FROM route_points WHERE routeId = :routeId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastPoint(routeId: Long): RoutePoint?

    @Query("DELETE FROM route_points WHERE routeId = :routeId")
    suspend fun deletePointsForRoute(routeId: Long)
}

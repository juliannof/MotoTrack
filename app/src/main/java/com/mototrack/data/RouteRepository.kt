package com.mototrack.data

import android.content.Context

class RouteRepository(context: Context) {
    private val dao = MotoTrackDatabase.getDatabase(context).routeDao()

    val allRoutes = dao.getAllRoutes()

    suspend fun insertRoute(route: Route) = dao.insertRoute(route)
    suspend fun updateRoute(route: Route) = dao.updateRoute(route)
    suspend fun deleteRoute(route: Route) = dao.deleteRoute(route)
    suspend fun getRouteById(id: Long) = dao.getRouteById(id)
    suspend fun getPointsForRoute(routeId: Long) = dao.getPointsForRoute(routeId)
    fun getPointsLive(routeId: Long) = dao.getPointsForRouteLive(routeId)
}

package com.mototrack.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.*
import com.mototrack.data.*
import com.mototrack.service.SensorLogger
import com.mototrack.service.TrackingService
import com.mototrack.utils.GpxExporter
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = RouteRepository(application)

    val allRoutes: LiveData<List<Route>> = repo.allRoutes

    // Relay desde el servicio
    val isRecording   = TrackingService.isRecording
    val currentSpeed  = TrackingService.currentSpeed
    val currentSpeedLimit = TrackingService.currentSpeedLimit
    val speedLimitEstimated = TrackingService.speedLimitEstimated
    val currentLean   = TrackingService.currentLean
    val currentLeanSigned = TrackingService.currentLeanSigned
    val currentAccel  = TrackingService.currentAccel
    val currentBearing = TrackingService.currentBearing
    val pointCount    = TrackingService.pointCount
    val maxSpeed      = TrackingService.maxSpeed
    val maxLean       = TrackingService.maxLean
    val maxLeanLeft   = TrackingService.maxLeanLeft
    val maxLeanRight  = TrackingService.maxLeanRight
    val maxAccel      = TrackingService.maxAccel
    val distanceKm    = TrackingService.distanceKm

    fun startTracking(routeName: String) {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
            putExtra(TrackingService.EXTRA_ROUTE_NAME, routeName)
        }
        ctx.startForegroundService(intent)
    }

    fun stopTracking() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
        }
        ctx.startService(intent)
    }

    fun deleteRoute(route: Route) = viewModelScope.launch {
        repo.deleteRoute(route)
        // El CSV de depuración vive fuera de Room: borrarlo a mano
        SensorLogger.fileFor(getApplication(), route.id).delete()
    }

    fun getPointsForRoute(routeId: Long) = viewModelScope.run {
        repo.getPointsLive(routeId)
    }

    suspend fun exportRouteAsGpx(routeId: Long): String? {
        val route = repo.getRouteById(routeId) ?: return null
        val points = repo.getPointsForRoute(routeId)
        return GpxExporter.toGpxString(route, points)
    }
}

package com.mototrack.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.*
import com.mototrack.auth.AuthRepository
import com.mototrack.data.*
import com.google.android.gms.maps.model.LatLng
import com.mototrack.service.SensorLogger
import com.mototrack.service.TrackingService
import com.mototrack.utils.GpxExporter
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = RouteRepository(application)

    private val auth = AuthRepository(application)

    // Cuenta con la sesión iniciada; el historial solo muestra sus rutas
    private val owner = MutableLiveData(auth.currentUser() ?: "")
    val allRoutes: LiveData<List<Route>> = owner.switchMap { repo.routesFor(it) }

    // Trazado de la ruta en el mapa del Dashboard. Vive aquí y no en el fragmento porque al girar
    // el móvil (p. ej. al sacarlo del soporte) el Dashboard se recrea y perdería el trazado.
    // Se queda tras detener la ruta y se borra al empezar otra (trailRouteId distinto).
    val trailPoints = mutableListOf<LatLng>()
    val trailSpeeds = mutableListOf<Float>()
    var trailRouteId: Long? = null

    /** Llamar al iniciar o cerrar sesión. */
    fun onSessionChanged() {
        val email = auth.currentUser() ?: ""
        if (email.isNotEmpty()) viewModelScope.launch { repo.claimUnownedRoutes(email) }
        owner.value = email
    }

    // Relay desde el servicio
    val isRecording   = TrackingService.isRecording
    val currentSpeed  = TrackingService.currentSpeed
    val currentSpeedLimit = TrackingService.currentSpeedLimit
    val avgSpeed      = TrackingService.avgSpeed
    val longitudinalAccel = TrackingService.longitudinalAccel
    val speedLimitEstimated = TrackingService.speedLimitEstimated
    val overSpeedLimit = TrackingService.overSpeedLimit
    val calibrationStatus = TrackingService.calibrationStatus
    val currentLean   = TrackingService.currentLean
    val currentLeanSigned = TrackingService.currentLeanSigned
    val currentAccel  = TrackingService.currentAccel
    val currentBearing = TrackingService.currentBearing
    val compassHeading = TrackingService.compassHeading
    val currentPlace = TrackingService.currentPlace
    val currentPosition = TrackingService.currentPosition
    val calibratedSinceAppStart = TrackingService.calibratedSinceAppStart
    val leanValid = TrackingService.leanValid
    val currentAltitude = TrackingService.currentAltitude
    val maxAltitude = TrackingService.maxAltitude
    val minAltitude = TrackingService.minAltitude
    val currentRouteId = TrackingService.currentRouteId
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

    suspend fun routePoints(routeId: Long) = repo.getPointsForRoute(routeId)

    suspend fun exportRouteAsGpx(routeId: Long): String? {
        val route = repo.getRouteById(routeId) ?: return null
        val points = repo.getPointsForRoute(routeId)
        return GpxExporter.toGpxString(route, points)
    }
}

package com.mototrack.utils

import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.mototrack.service.TrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Mantiene al día el nombre del lugar donde está la moto (urbanización o calle) para
 * mostrarlo en el Dashboard. Consulta la red, así que se limita: solo si te has movido
 * más de 60 m desde la última vez, con 15 s de pausa mínima y una consulta a la vez.
 */
object PlaceTracker {

    private const val MIN_DISTANCE_M = 60f
    private const val MIN_INTERVAL_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastPoint: Location? = null
    private var lastTimeMs = 0L
    private var inFlight = false

    @Synchronized
    fun update(context: Context, lat: Double, lon: Double) {
        if (inFlight) return
        val now = SystemClock.elapsedRealtime()
        val here = Location("place").apply { latitude = lat; longitude = lon }
        val prev = lastPoint
        if (prev != null && (now - lastTimeMs < MIN_INTERVAL_MS || prev.distanceTo(here) < MIN_DISTANCE_M)) return
        inFlight = true
        lastPoint = here
        lastTimeMs = now
        val appContext = context.applicationContext
        scope.launch {
            try {
                RouteNamer.nameAt(appContext, lat, lon)?.let { TrackingService.currentPlace.postValue(it) }
            } finally {
                synchronized(this@PlaceTracker) { inFlight = false }
            }
        }
    }
}

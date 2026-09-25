package com.mototrack.utils

import android.content.Context
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mototrack.service.TrackingService

/**
 * Mantiene la altura del Dashboard al día cuando NO se está grabando (el GPS de
 * la grabación lo arranca el servicio). Se enciende con el Dashboard a la vista
 * y se apaga al salir o al empezar a grabar, para no gastar batería de más.
 */
class AltitudeMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val fused = LocationServices.getFusedLocationProviderClient(appContext)
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val msl = MslAltitude(appContext)
    private var running = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                msl.ofOrNull(loc)?.let { TrackingService.currentAltitude.postValue(it) }
            }
        }
    }

    fun start() {
        if (running) return
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L).build()
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            locationManager.addNmeaListener(msl.nmeaListener, Handler(Looper.getMainLooper()))
            running = true
        } catch (e: SecurityException) {
            Log.w("AltitudeMonitor", "Sin permiso de ubicación: ${e.message}")
        }
    }

    fun stop() {
        if (!running) return
        fused.removeLocationUpdates(callback)
        locationManager.removeNmeaListener(msl.nmeaListener)
        running = false
    }
}

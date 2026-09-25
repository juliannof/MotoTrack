package com.mototrack.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
    private val sensors = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotMatrix = FloatArray(9)

    // Rumbo por la brújula del móvil cuando no se graba (parado o sin GPS)
    private val compassListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            Compass.azimuth(rotMatrix)?.let { az ->
                val prev = TrackingService.compassHeading.value
                if (prev == null || Math.abs(((prev - az + 540f) % 360f) - 180f) >= 2f) {
                    TrackingService.compassHeading.postValue(az)
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                PlaceTracker.update(appContext, loc.latitude, loc.longitude)
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
            sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                sensors.registerListener(compassListener, it, SensorManager.SENSOR_DELAY_UI)
            }
            running = true
        } catch (e: SecurityException) {
            Log.w("AltitudeMonitor", "Sin permiso de ubicación: ${e.message}")
        }
    }

    fun stop() {
        if (!running) return
        fused.removeLocationUpdates(callback)
        locationManager.removeNmeaListener(msl.nmeaListener)
        sensors.unregisterListener(compassListener)
        running = false
    }
}

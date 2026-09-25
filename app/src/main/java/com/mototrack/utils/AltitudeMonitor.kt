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

    /** Velocidad (km/h) de cada posición mientras el monitor está activo: sirve para detectar el inicio de movimiento. */
    var onSpeed: ((Float) -> Unit)? = null

    /**
     * Movimiento detectado desde parado: cualquier cosa cuenta, ya sea el GPS, la aceleración
     * o la inclinación. Recibe el motivo ("GPS", "aceleración" o "inclinación").
     */
    var onMotion: ((String) -> Unit)? = null

    private var lastMotionMs = 0L
    private fun motion(reason: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastMotionMs < MOTION_DEBOUNCE_MS) return
        lastMotionMs = now
        onMotion?.invoke(reason)
    }

    // Inclinación: cambio de la dirección de la gravedad respecto a una referencia que se
    // actualiza despacio (así una deriva lenta no cuenta). Solo con el móvil erguido, como en
    // el soporte: sobre una mesa o en la mano no dispara.
    private var baselineUp: FloatArray? = null
    private var accelEma = 0f
    private val sensors = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotMatrix = FloatArray(9)

    // Rumbo por la brújula del móvil cuando no se graba (parado o sin GPS)
    private val compassListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            detectTilt()
            Compass.azimuth(rotMatrix)?.let { az ->
                val prev = TrackingService.compassHeading.value
                if (prev == null || Math.abs(((prev - az + 540f) % 360f) - 180f) >= 2f) {
                    TrackingService.compassHeading.postValue(az)
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun upright() = Math.hypot(rotMatrix[2].toDouble(), rotMatrix[5].toDouble()) >= MIN_UPRIGHT

    private fun detectTilt() {
        if (!upright()) { baselineUp = null; return }
        // "Arriba" del mundo en coordenadas del móvil (fila 3 de la matriz de rotación)
        val up = floatArrayOf(rotMatrix[6], rotMatrix[7], rotMatrix[8])
        val base = baselineUp
        if (base == null) { baselineUp = up; return }
        val dot = (up[0] * base[0] + up[1] * base[1] + up[2] * base[2]).coerceIn(-1f, 1f)
        if (Math.toDegrees(Math.acos(dot.toDouble())) >= TILT_MOTION_DEG) {
            baselineUp = up
            motion("inclinación")
        } else {
            for (i in 0..2) base[i] += TILT_BASELINE_ALPHA * (up[i] - base[i])
        }
    }

    // Aceleración en el sentido de la marcha, como en el servicio de grabación
    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!upright()) { accelEma = 0f; return }
            val a = event.values
            val zx = rotMatrix[2]; val zy = rotMatrix[5]
            val h = Math.hypot(zx.toDouble(), zy.toDouble()).toFloat()
            val fx = -zx / h; val fy = -zy / h
            val east = rotMatrix[0] * a[0] + rotMatrix[1] * a[1] + rotMatrix[2] * a[2]
            val north = rotMatrix[3] * a[0] + rotMatrix[4] * a[1] + rotMatrix[5] * a[2]
            accelEma += 0.1f * ((east * fx + north * fy) - accelEma)
            if (Math.abs(accelEma) >= ACCEL_MOTION_MS2) motion("aceleración")
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                PlaceTracker.update(appContext, loc.latitude, loc.longitude)
                if (loc.hasSpeed() && loc.accuracy <= AUTO_START_MAX_ACCURACY_M) {
                    val kmh = loc.speed * 3.6f
                    onSpeed?.invoke(kmh)
                    if (kmh >= GPS_MOTION_KMH) motion("GPS")
                }
                TrackingService.currentPosition.postValue(doubleArrayOf(loc.latitude, loc.longitude))
                msl.ofOrNull(loc)?.let { TrackingService.currentAltitude.postValue(it) }
            }
        }
    }

    fun start() {
        if (running) return
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            locationManager.addNmeaListener(msl.nmeaListener, Handler(Looper.getMainLooper()))
            sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                sensors.registerListener(compassListener, it, SensorManager.SENSOR_DELAY_GAME)
            }
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensors.registerListener(accelListener, it, SensorManager.SENSOR_DELAY_GAME)
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
        sensors.unregisterListener(accelListener)
        baselineUp = null
        accelEma = 0f
        running = false
    }

    private companion object {
        // Posiciones peores que esto no valen para decidir que hay movimiento
        const val AUTO_START_MAX_ACCURACY_M = 30f
        // Desde parado, cualquiera de estas señales es movimiento
        const val GPS_MOTION_KMH = 4f
        const val TILT_MOTION_DEG = 4.0
        const val TILT_BASELINE_ALPHA = 0.02f
        const val ACCEL_MOTION_MS2 = 1.0f
        const val MIN_UPRIGHT = 0.5          // pantalla casi vertical: móvil en el soporte
        const val MOTION_DEBOUNCE_MS = 2_000L
    }
}

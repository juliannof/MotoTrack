package com.mototrack.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.*
import android.location.*
import android.os.*
import android.util.Log
import java.util.Locale
import android.view.Display
import android.view.Surface
import android.hardware.display.DisplayManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.mototrack.R
import com.mototrack.data.*
import com.mototrack.ui.MainActivity
import com.mototrack.utils.GpxExporter
import com.mototrack.utils.RouteNamer
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * Servicio en primer plano que gestiona:
 *   - Ubicación (fused: GPS + WiFi/red móvil) — posición, velocidad, bearing
 *   - Acelerómetro (aceleración en 3 ejes)
 *   - Giroscopio (ángulo de inclinación lateral — lean angle)
 *   - Persistencia en Room DB
 */
class TrackingService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "ACTION_START_TRACKING"
        const val ACTION_STOP  = "ACTION_STOP_TRACKING"
        const val EXTRA_ROUTE_NAME = "EXTRA_ROUTE_NAME"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mototrack_channel"
        private const val TAG = "TrackingService"

        // Por encima de este error (m) la posición viene de WiFi/antenas, no del
        // GPS: se guarda el punto, pero no se suma a la distancia (salta decenas
        // de metros aunque estés quieto)
        private const val MAX_ACCURACY_FOR_DISTANCE_M = 25f

        // Clave nueva: los offsets guardados con el cálculo antiguo (roll de
        // getOrientation) no valen para la inclinación lateral actual
        private const val PREF_LEAN_OFFSET = "resting_lean_offset"

        // LiveData compartida para la UI
        val currentSpeed    = MutableLiveData(0f)        // km/h
        val currentLean     = MutableLiveData(0f)        // grados (valor absoluto)
        val currentLeanSigned = MutableLiveData(0f)      // grados: - izquierda, + derecha
        val currentAccel    = MutableLiveData(0f)        // m/s²
        val currentBearing  = MutableLiveData(0f)        // grados
        val currentAltitude = MutableLiveData(0.0)       // metros
        val isRecording     = MutableLiveData(false)
        val currentRouteId  = MutableLiveData<Long?>(null)
        val pointCount      = MutableLiveData(0)

        // Estadísticas en tiempo real
        val maxSpeed        = MutableLiveData(0f)
        val maxLean         = MutableLiveData(0f)
        val maxAccel        = MutableLiveData(0f)
        val distanceKm      = MutableLiveData(0f)
    }

    // ── Coroutines ────────────────────────────────────────────────────────────
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── DB ────────────────────────────────────────────────────────────────────
    private lateinit var db: MotoTrackDatabase

    // ── Sensores ──────────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var rotVectorSensor: Sensor? = null

    // Valores raw de sensores
    private val gravity   = FloatArray(3)          // para separar gravedad
    private val linearAcc = FloatArray(3)          // aceleración sin gravedad
    private val rotMatrix = FloatArray(9)

    // Lean angle calculado
    private var currentLeanDeg = 0f
    private var accelMagnitude = 0f

    // ── GPS ───────────────────────────────────────────────────────────────────
    private lateinit var fusedClient: FusedLocationProviderClient
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocationChanged)
        }
    }
    private var lastLocation: Location? = null
    private var lastGpsSpeed = 0f
    private var lastGpsBearing = 0f
    private var restingAngleOffset = 0f
    private var rawLeanAngle = 0f
    private val calibrationBuffer = mutableListOf<Float>()
    val calibrationDone = MutableLiveData<Float>()
    private var lastGpsAlt = 0.0

    // ── Estado de ruta ────────────────────────────────────────────────────────
    private var activeRouteId: Long? = null
    private var totalDistance = 0f
    private var pointsRecorded = 0

    // ── Registro de depuración (CSV a 10 Hz) ──────────────────────────────────
    private var sensorLogger: SensorLogger? = null
    private var trackingStartMs = 0L
    private var signalLost = false
    private var lastSource = "none"

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        db = MotoTrackDatabase.getDatabase(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        loadCalibration()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val routeName = intent.getStringExtra(EXTRA_ROUTE_NAME) ?: "Ruta ${System.currentTimeMillis()}"
                startTracking(routeName)
            }
            ACTION_STOP -> stopTracking()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
        serviceScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tracking Start / Stop
    // ─────────────────────────────────────────────────────────────────────────

    private fun startTracking(routeName: String) {
        startForeground(NOTIFICATION_ID, buildNotification("Grabando ruta..."))

        // Reset stats
        totalDistance = 0f
        pointsRecorded = 0
        maxSpeed.postValue(0f)
        maxLean.postValue(0f)
        maxAccel.postValue(0f)
        distanceKm.postValue(0f)

        // Crear ruta en DB
        serviceScope.launch {
            val route = Route(
                name = routeName,
                startTime = System.currentTimeMillis()
            )
            val id = db.routeDao().insertRoute(route)
            activeRouteId = id
            currentRouteId.postValue(id)
            isRecording.postValue(true)

            trackingStartMs = System.currentTimeMillis()
            signalLost = false
            lastSource = "none"
            sensorLogger = SensorLogger(SensorLogger.fileFor(this@TrackingService, id)).also {
                it.start(serviceScope, ::sensorLogRow)
            }
            Log.i(TAG, "Ruta $id iniciada: \"$routeName\" (offset inclinación ${fmt(restingAngleOffset)}°)")
        }

        registerSensors()
        registerGps()
    }

    private fun stopTracking() {
        // Se llama desde DETENER y otra vez desde onDestroy: solo la primera
        // vez hay ruta que cerrar
        val routeId = activeRouteId
        activeRouteId = null

        sensorLogger?.stop()
        sensorLogger = null
        if (routeId != null) {
            Log.i(TAG, "Ruta $routeId detenida: $pointsRecorded puntos, ${fmt(totalDistance)} km, " +
                "máx ${fmt(maxSpeed.value ?: 0f)} km/h, inclinación máx ${fmt(maxLean.value ?: 0f)}°")
        }

        unregisterSensors()
        unregisterGps()

        // La UI cambia ya (estamos en el hilo principal), sin esperar a la BD
        isRecording.value = false
        currentRouteId.value = null

        if (routeId != null) {
            val distance = totalDistance
            val maxSpeedKmh = maxSpeed.value ?: 0f
            val maxLeanAngle = maxLean.value ?: 0f
            val maxAcceleration = maxAccel.value ?: 0f

            // NonCancellable: stopSelf() dispara onDestroy, que cancela
            // serviceScope. Sin esto el cierre de la ruta se abortaba a medias
            // y DETENER dejaba de responder.
            serviceScope.launch(NonCancellable) {
                val route = db.routeDao().getRouteById(routeId) ?: return@launch
                val points = db.routeDao().getPointsForRoute(routeId)
                val avgSpeed = if (points.isNotEmpty()) points.map { it.speedKmh }.average().toFloat() else 0f

                // Nombre "De A a B por C" si el usuario dejó el nombre por defecto
                val placeName = if (RouteNamer.isDefaultName(route.name)) {
                    RouteNamer.describe(this@TrackingService, points)
                } else null
                if (placeName != null) Log.i(TAG, "Ruta $routeId renombrada: \"$placeName\"")

                db.routeDao().updateRoute(
                    route.copy(
                        name = placeName ?: route.name,
                        endTime = System.currentTimeMillis(),
                        distanceKm = distance,
                        maxSpeedKmh = maxSpeedKmh,
                        avgSpeedKmh = avgSpeed,
                        maxLeanAngle = maxLeanAngle,
                        maxAcceleration = maxAcceleration,
                        isCompleted = true
                    )
                )
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GPS
    // ─────────────────────────────────────────────────────────────────────────

    private fun registerGps() {
        try {
            // Fused elige la fuente: GPS con cielo despejado y WiFi/red móvil
            // cuando no hay señal GPS (interiores, túneles, arranque en frío)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L) // cada 1 segundo
                .setMinUpdateDistanceMeters(1f)                                          // mínimo 1 metro
                .build()
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "GPS permission denied: ${e.message}")
        }
    }

    private fun unregisterGps() {
        fusedClient.removeLocationUpdates(locationCallback)
    }

    private fun onLocationChanged(location: Location) {
        if (signalLost) {
            signalLost = false
            Log.i(TAG, "Posición recuperada (±${location.accuracy.toInt()} m)")
        }
        val source = sourceOf(location)
        if (source != lastSource) {
            Log.i(TAG, "Fuente de posición: $lastSource → $source (±${location.accuracy.toInt()} m)")
            lastSource = source
        }

        // Calcular distancia incremental
        lastLocation?.let { prev ->
            val dist = prev.distanceTo(location) / 1000f  // km
            val precise = location.accuracy <= MAX_ACCURACY_FOR_DISTANCE_M &&
                prev.accuracy <= MAX_ACCURACY_FOR_DISTANCE_M
            if (dist < 0.5f && precise) {  // filtrar saltos erróneos y fixes por red
                totalDistance += dist
                distanceKm.postValue(totalDistance)
            }
        }

        lastGpsSpeed   = location.speed * 3.6f   // m/s → km/h
        lastGpsBearing = location.bearing
        lastGpsAlt     = location.altitude
        lastLocation   = location

        currentSpeed.postValue(lastGpsSpeed)
        currentBearing.postValue(lastGpsBearing)
        currentAltitude.postValue(lastGpsAlt)

        if (lastGpsSpeed > (maxSpeed.value ?: 0f)) {
            maxSpeed.postValue(lastGpsSpeed)
        }

        // Guardar punto
        savePoint(location)


        // Auto-calibración a baja velocidad
        if (lastGpsSpeed < 20f) {
            calibrationBuffer.add(rawLeanAngle)
            if (calibrationBuffer.size > 30) calibrationBuffer.removeAt(0)
            if (calibrationBuffer.size >= 10) {
                val avg = calibrationBuffer.average().toFloat()
                val stdDev = calibrationBuffer.map { (it - avg) * (it - avg) }
                    .average().let { Math.sqrt(it).toFloat() }
                if (stdDev < 2.0f) {
                    if (abs(avg - restingAngleOffset) > 0.5f) {
                        Log.i(TAG, "Calibración automática: offset ${fmt(restingAngleOffset)}° → ${fmt(avg)}°")
                    }
                    restingAngleOffset = avg
                    getSharedPreferences("mototrack_prefs", Context.MODE_PRIVATE)
                        .edit().putFloat(PREF_LEAN_OFFSET, restingAngleOffset).apply()
                }
            }
        } else {
            calibrationBuffer.clear()
        }


        // Actualizar notificación
        updateNotification()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sensores
    // ─────────────────────────────────────────────────────────────────────────

    private fun registerSensors() {
        accelSensor       = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor        = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rotVectorSensor   = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        rotVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {

            Sensor.TYPE_ACCELEROMETER -> {
                // Filtro paso-bajo para aislar la gravedad
                val alpha = 0.8f
                gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
                gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
                gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

                // Aceleración lineal (sin gravedad)
                linearAcc[0] = event.values[0] - gravity[0]
                linearAcc[1] = event.values[1] - gravity[1]
                linearAcc[2] = event.values[2] - gravity[2]

                accelMagnitude = sqrt(
                    linearAcc[0].pow(2) + linearAcc[1].pow(2) + linearAcc[2].pow(2)
                )

                currentAccel.postValue(accelMagnitude)
                if (accelMagnitude > (maxAccel.value ?: 0f)) {
                    maxAccel.postValue(accelMagnitude)
                }
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                rawLeanAngle = lateralLeanDegrees()
                val correctedLean = rawLeanAngle - restingAngleOffset
                currentLeanDeg = correctedLean
                currentLean.postValue(abs(correctedLean))
                currentLeanSigned.postValue(correctedLean)
                if (abs(correctedLean) > (maxLean.value ?: 0f)) {
                    maxLean.postValue(abs(correctedLean))
                }
            }
        }
    }

    /**
     * Inclinación lateral (izquierda/derecha) de la moto, en grados.
     * Positivo = inclinada a la derecha, negativo = a la izquierda.
     *
     * Con el móvil en un soporte de manillar la pantalla mira al piloto, así
     * que tumbarse en una curva gira el móvil *en el plano de la pantalla*.
     * Medimos el ángulo entre el "arriba" de la pantalla y la vertical real:
     * inclinar la pantalla hacia delante/atrás no cambia este ángulo.
     * (El roll de getOrientation() medía otra cosa: con el móvil vertical u
     * horizontal mezclaba el cabeceo delante/atrás.)
     */
    private fun lateralLeanDegrees(): Float {
        // Fila 3 de la matriz de rotación = eje "arriba" del mundo expresado
        // en coordenadas del dispositivo (x derecha, y arriba en vertical)
        val ux = rotMatrix[6]
        val uy = rotMatrix[7]

        // Pasar a coordenadas de pantalla según la rotación actual, para que
        // funcione igual con el soporte en vertical o en horizontal
        val (sx, sy) = when (displayRotation()) {
            Surface.ROTATION_90  -> -uy to ux
            Surface.ROTATION_180 -> -ux to -uy
            Surface.ROTATION_270 -> uy to -ux
            else                 -> ux to uy
        }
        return Math.toDegrees(atan2(-sx, sy).toDouble()).toFloat()
    }

    /** GPS si el error es pequeño; si no, la posición viene de WiFi/antenas. */
    private fun sourceOf(location: Location) =
        if (location.accuracy <= MAX_ACCURACY_FOR_DISTANCE_M) "gps" else "red"

    private fun fmt(v: Float) = String.format(Locale.US, "%.1f", v)

    /** Fila del CSV de depuración con el estado actual. Se llama desde otro hilo cada 100 ms. */
    private fun sensorLogRow(): String {
        val now = System.currentTimeMillis()
        val loc = lastLocation
        val fixAgeS = loc?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1e9 }

        // Parado no llegan posiciones (mínimo 1 m entre fixes), así que solo
        // avisamos si íbamos a más de 10 km/h: eso sí es pérdida de señal
        if (fixAgeS != null && fixAgeS > 5 && lastGpsSpeed > 10f && !signalLost) {
            signalLost = true
            Log.w(TAG, "Sin posición desde hace ${fixAgeS.toInt()} s yendo a ${lastGpsSpeed.toInt()} km/h")
        }

        val rotationDeg = when (displayRotation()) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return buildString {
            append(now).append(',')
            append(String.format(Locale.US, "%.1f", (now - trackingStartMs) / 1000.0)).append(',')
            append(String.format(Locale.US, "%.2f,%.2f,%.2f,", rawLeanAngle, restingAngleOffset, currentLeanDeg))
            append(rotationDeg).append(',')
            append(String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f,", linearAcc[0], linearAcc[1], linearAcc[2], accelMagnitude))
            append(String.format(Locale.US, "%.1f,", lastGpsSpeed))
            if (loc != null) {
                append(String.format(Locale.US, "%.7f,%.7f,%.1f,%.1f,", loc.latitude, loc.longitude, loc.accuracy, fixAgeS))
                append(sourceOf(loc))
            } else {
                append(",,,,none")
            }
        }
    }

    private fun displayRotation(): Int =
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Persistencia
    // ─────────────────────────────────────────────────────────────────────────

    private fun savePoint(location: Location) {
        val routeId = activeRouteId ?: return

        serviceScope.launch {
            val point = RoutePoint(
                routeId       = routeId,
                timestamp     = System.currentTimeMillis(),
                latitude      = location.latitude,
                longitude     = location.longitude,
                altitude      = location.altitude,
                accuracy      = location.accuracy,
                speedKmh      = lastGpsSpeed,
                accelX        = linearAcc[0],
                accelY        = linearAcc[1],
                accelZ        = linearAcc[2],
                accelTotal    = accelMagnitude,
                leanAngle     = currentLeanDeg,
                bearing       = location.bearing,
                hdop          = if (location.hasAccuracy()) location.accuracy else -1f,
                vdop          = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else -1f,
                satellites    = location.extras?.getInt("satellites") ?: 0,
                altitudeEllipsoid = if (location.hasMslAltitude()) location.mslAltitudeMeters else location.altitude
            )
            db.routeDao().insertPoint(point)
            pointsRecorded++
            pointCount.postValue(pointsRecorded)
        }
    }

    private fun loadCalibration() {
        val prefs = getSharedPreferences("mototrack_prefs", Context.MODE_PRIVATE)
        restingAngleOffset = prefs.getFloat(PREF_LEAN_OFFSET, 0f)
    }

    fun calibrate() {
        val samples = mutableListOf<Float>()
        val handler = Handler(Looper.getMainLooper())
        val sampleRunnable = object : Runnable {
            override fun run() {
                samples.add(rawLeanAngle)
                if (samples.size < 50) {
                    handler.postDelayed(this, 40)
                } else {
                    restingAngleOffset = samples.average().toFloat()
                    getSharedPreferences("mototrack_prefs", Context.MODE_PRIVATE)
                        .edit().putFloat(PREF_LEAN_OFFSET, restingAngleOffset).apply()
                    calibrationDone.postValue(restingAngleOffset)
                }
            }
        }
        handler.post(sampleRunnable)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notificación
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MotoTrack GPS",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Grabación de ruta activa"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🏍️ MotoTrack")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_moto)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val speed = String.format("%.0f km/h | %.1f km", lastGpsSpeed, totalDistance)
        nm.notify(NOTIFICATION_ID, buildNotification(speed))
    }
}

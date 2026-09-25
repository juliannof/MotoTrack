package com.mototrack.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.*
import android.location.*
import android.media.AudioManager
import android.media.ToneGenerator
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
import com.mototrack.auth.AuthRepository
import com.mototrack.data.*
import com.mototrack.utils.MslAltitude
import com.mototrack.utils.SpeedLimitCache
import com.mototrack.ui.MainActivity
import com.mototrack.utils.GpxExporter
import com.mototrack.utils.RouteNamer
import com.mototrack.utils.SpeedLimitProvider
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
        // Una moto no pasa de ~65° de inclinación; más allá es ruido de orientación
        private const val MAX_PLAUSIBLE_LEAN_DEG = 70f
        // Módulo mínimo del "arriba" proyectado en la pantalla (0.5 ≈ pantalla a ≤60° de la vertical)
        private const val MIN_SCREEN_VERTICALITY = 0.5f

        // Autocalibración del cero de inclinación. Solo en los primeros 2 minutos de la
        // ruta y solo rodando entre 5 y 20 km/h en línea recta: parado no se calibra (el
        // móvil puede estar en la mano o sin montar) y en una curva lenta el lean no es
        // cero (visto en la ruta 40: offset -28,8°). Ventana de 3 s con el ángulo estable.
        private const val CALIB_PHASE_MS = 120_000L
        private const val CALIB_MIN_SPEED_KMH = 5f
        private const val CALIB_MAX_SPEED_KMH = 20f
        private const val CALIB_MAX_LATERAL_MS2 = 0.5f
        private const val CALIB_WINDOW_MS = 3_000L
        // Rodando, la dirección se corrige algo: más margen que parado
        private const val CALIB_MAX_STD_DEG = 1.0
        // Una inclinación del soporte mayor que esto no es del soporte: se ignora
        private const val CALIB_MAX_OFFSET_DEG = 15f

        // Límite de la vía: no saturar Overpass (uso justo) ni gastar datos de más
        // Suavizado de la aceleración longitudinal (~0,3 s a la frecuencia del acelerómetro)
        private const val ACCEL_SMOOTHING = 0.1f
        private const val LATERAL_SMOOTHING = 0.05f

        // Velocidad "caducada": sin fix nuevo, ver startStaleSpeedWatchdog
        private const val STALE_FIX_STOPPED_S = 2.5
        private const val STALE_FIX_SLOW_KMH = 15f
        private const val STALE_FIX_LOST_S = 8.0
        // Por debajo de esto es ruido y vibración del móvil, no aceleración de marcha
        private const val ACCEL_DEADBAND = 0.4f

        // Con la caché local, la red solo se usa en tramos nuevos: se puede preguntar antes
        private const val LIMIT_QUERY_MIN_DISTANCE_M = 30f
        private const val LIMIT_QUERY_MIN_INTERVAL_MS = 4_000L
        // Si Overpass falla (504, sin datos), esperar antes de insistir
        private const val LIMIT_FAIL_BACKOFF_MS = 10_000L

        // Aviso al rebasar el límite: margen del GPS, y cada cuánto se repite el pitido
        private const val OVER_LIMIT_TOLERANCE_KMH = 3f
        private const val OVER_LIMIT_REPEAT_MS = 15_000L

        // LiveData compartida para la UI
        val currentSpeed    = MutableLiveData(0f)        // km/h
        val currentSpeedLimit = MutableLiveData(0)       // km/h de la vía; 0 = desconocido
        val speedLimitEstimated = MutableLiveData(false) // true: deducido del tipo de vía
        val calibrationStatus = MutableLiveData(CalibrationStatus.OFF) // calibración del cero de inclinación
        val overSpeedLimit  = MutableLiveData(false)     // true: por encima del límite (+ margen)
        val avgSpeed        = MutableLiveData(0f)        // km/h, media de la ruta en curso
        val longitudinalAccel = MutableLiveData(0f)      // m/s²: + acelerando, - frenando (sentido de la marcha)
        val currentLean     = MutableLiveData(0f)        // grados (valor absoluto)
        val currentLeanSigned = MutableLiveData(0f)      // grados: - izquierda, + derecha
        val currentAccel    = MutableLiveData(0f)        // m/s²
        val currentBearing  = MutableLiveData(0f)        // grados
        val currentAltitude = MutableLiveData(0.0)       // metros
        // Alturas extremas de la ruta en curso (m); NaN = todavía sin dato. Pueden ser
        // negativas: hay rutas que pasan por debajo del nivel del mar.
        val minAltitude     = MutableLiveData(Double.NaN)
        val maxAltitude     = MutableLiveData(Double.NaN)
        val isRecording     = MutableLiveData(false)
        val currentRouteId  = MutableLiveData<Long?>(null)
        val pointCount      = MutableLiveData(0)

        // Estadísticas en tiempo real
        val maxSpeed        = MutableLiveData(0f)
        val maxLean         = MutableLiveData(0f)        // máximo de ambos lados
        val maxLeanLeft     = MutableLiveData(0f)
        val maxLeanRight    = MutableLiveData(0f)
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

    // Media de velocidad de la ruta (misma definición que la que se guarda: media de los puntos)
    private var speedSumKmh = 0.0
    private var speedSamples = 0
    // Estado del filtro y valor que se muestra/guarda: el filtro sigue continuo y la
    // zona muerta solo recorta lo que sale (parado no debe bailar entre +0.0 y -0.1)
    private var accelFilter = 0f
    private var smoothedAccel = 0f

    // Consulta del límite de velocidad
    private var limitQueryLocation: Location? = null
    private var limitQueryTimeMs = 0L
    private var limitQueryInFlight = false
    private val limitLock = Any()

    // Límite vigente. Se conserva cuando una vía no trae dato: se sigue con el último conocido
    @Volatile private var knownLimit = 0
    private var overLimit = false
    private var lastOverAlertMs = 0L
    private var toneGenerator: ToneGenerator? = null
    @Volatile private var lastGpsSpeed = 0f
    private var staleSpeedJob: Job? = null
    private var lastGpsBearing = 0f
    private var restingAngleOffset = 0f
    private var rawLeanAngle = 0f
    // Ventana de autocalibración con la moto parada (a la frecuencia del sensor)
    private var stillStartMs = 0L
    private var stillN = 0
    // Aceleración lateral media (valor absoluto, filtrada): ~0 en recta, alta en curva
    @Volatile private var lateralAccelAbs = 0f
    // Fase inicial de calibración de la ruta: cuándo empezó, si ya se calibró y si ya se cerró
    private var calibStartMs = 0L
    private var calibratedThisRide = false
    private var calibPhaseOver = false
    private var stillSum = 0.0
    private var stillSumSq = 0.0
    val calibrationDone = MutableLiveData<Float>()
    private var lastGpsAlt = 0.0

    private val msl by lazy { MslAltitude(this) }

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
        toneGenerator?.release()
        toneGenerator = null
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
        currentSpeedLimit.postValue(0)
        speedLimitEstimated.postValue(false)
        knownLimit = 0
        overLimit = false
        overSpeedLimit.postValue(false)
        speedSumKmh = 0.0
        speedSamples = 0
        accelFilter = 0f
        smoothedAccel = 0f
        maxAltitude.postValue(Double.NaN)
        minAltitude.postValue(Double.NaN)
        calibratedThisRide = false
        calibPhaseOver = false
        calibStartMs = SystemClock.elapsedRealtime()
        stillN = 0
        calibrationStatus.postValue(CalibrationStatus.WAITING)
        avgSpeed.postValue(0f)
        longitudinalAccel.postValue(0f)
        limitQueryLocation = null
        maxLeanLeft.postValue(0f)
        maxLeanRight.postValue(0f)
        maxAccel.postValue(0f)
        distanceKm.postValue(0f)

        // Crear ruta en DB
        serviceScope.launch {
            val route = Route(
                name = routeName,
                ownerEmail = AuthRepository(this@TrackingService).currentUser() ?: "",
                startTime = System.currentTimeMillis()
            )
            val id = db.routeDao().insertRoute(route)
            activeRouteId = id
            currentRouteId.postValue(id)
            isRecording.postValue(true)
            startStaleSpeedWatchdog()

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
        calibrationStatus.postValue(CalibrationStatus.OFF)
        // Se llama desde DETENER y otra vez desde onDestroy: solo la primera
        // vez hay ruta que cerrar
        val routeId = activeRouteId
        activeRouteId = null

        staleSpeedJob?.cancel()
        staleSpeedJob = null
        sensorLogger?.stop()
        sensorLogger = null
        if (routeId != null) {
            Log.i(TAG, "Ruta $routeId detenida: $pointsRecorded puntos, ${fmt(totalDistance)} km, " +
                "máx ${fmt(maxSpeed.value ?: 0f)} km/h, inclinación máx izq ${fmt(maxLeanLeft.value ?: 0f)}° / der ${fmt(maxLeanRight.value ?: 0f)}°")
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
            val maxLeanLeftDeg = maxLeanLeft.value ?: 0f
            val maxLeanRightDeg = maxLeanRight.value ?: 0f
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
                        maxLeanLeft = maxLeanLeftDeg,
                        maxLeanRight = maxLeanRightDeg,
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
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                .addNmeaListener(msl.nmeaListener, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            Log.e(TAG, "GPS permission denied: ${e.message}")
        }
    }

    private fun unregisterGps() {
        fusedClient.removeLocationUpdates(locationCallback)
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeNmeaListener(msl.nmeaListener)
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
        lastGpsAlt     = msl.of(location)
        lastLocation   = location

        speedSumKmh += lastGpsSpeed
        speedSamples++
        avgSpeed.postValue((speedSumKmh / speedSamples).toFloat())

        currentSpeed.postValue(lastGpsSpeed)
        updateOverLimit()
        currentBearing.postValue(lastGpsBearing)
        currentAltitude.postValue(lastGpsAlt)
        val hi = maxAltitude.value ?: Double.NaN
        val lo = minAltitude.value ?: Double.NaN
        if (hi.isNaN() || lastGpsAlt > hi) maxAltitude.postValue(lastGpsAlt)
        if (lo.isNaN() || lastGpsAlt < lo) minAltitude.postValue(lastGpsAlt)

        if (lastGpsSpeed > (maxSpeed.value ?: 0f)) {
            maxSpeed.postValue(lastGpsSpeed)
        }

        // Guardar punto
        savePoint(location)

        updateSpeedLimit(location)


        // Actualizar notificación
        updateNotification()
    }

    /**
     * Aceleración en el sentido de la marcha (+ acelerando, - frenando).
     *
     * Con el móvil en el manillar y la pantalla mirando al piloto (la misma
     * suposición que el cálculo de inclinación), "adelante" es la normal de la
     * pantalla en sentido contrario, proyectada en horizontal. Pasamos la
     * aceleración del móvil al sistema del mundo (x = este, y = norte) con la
     * matriz de rotación y nos quedamos con la parte que apunta hacia delante.
     * La fuerza lateral de las curvas es perpendicular y no entra; la gravedad
     * es vertical y tampoco. No necesita GPS, así que funciona parado.
     */
    private fun updateLongitudinalAccel(a: FloatArray) {
        // Eje z del móvil (sale de la pantalla, hacia el piloto) en coordenadas del mundo
        val zx = rotMatrix[2]
        val zy = rotMatrix[5]
        val horiz = hypot(zx, zy)

        // Móvil tumbado (pantalla hacia arriba): no hay "adelante" definido
        var lateral = 0f
        val forward = if (horiz < MIN_SCREEN_VERTICALITY) 0f else {
            val fx = -zx / horiz
            val fy = -zy / horiz
            val east  = rotMatrix[0] * a[0] + rotMatrix[1] * a[1] + rotMatrix[2] * a[2]
            val north = rotMatrix[3] * a[0] + rotMatrix[4] * a[1] + rotMatrix[5] * a[2]
            // Perpendicular a "adelante": la fuerza de las curvas
            lateral = east * fy - north * fx
            east * fx + north * fy
        }
        lateralAccelAbs += LATERAL_SMOOTHING * (abs(lateral) - lateralAccelAbs)
        accelFilter += ACCEL_SMOOTHING * (forward - accelFilter)
        smoothedAccel = if (abs(accelFilter) < ACCEL_DEADBAND) 0f else accelFilter
        longitudinalAccel.postValue(smoothedAccel)
        // Máxima aceleración de la ruta: pico hacia delante (no las vibraciones)
        if (smoothedAccel > (maxAccel.value ?: 0f)) maxAccel.postValue(smoothedAccel)
    }

    /**
     * Límite de la vía: primero la caché local (instantánea) y solo si el tramo
     * no se conoce, Overpass (con pausa mínima entre consultas).
     */
    private fun updateSpeedLimit(location: Location) {
        if (sourceOf(location) != "gps" || lastGpsSpeed < 3f) return
        val bearing = if (location.hasBearing() && lastGpsSpeed > 10f) location.bearing else null

        serviceScope.launch {
            val cacheDao = db.speedLimitCacheDao()
            SpeedLimitCache.get(cacheDao, location.latitude, location.longitude, bearing)?.let {
                applyLimit(it)
                return@launch
            }

            val now = SystemClock.elapsedRealtime()
            synchronized(limitLock) {
                val prev = limitQueryLocation
                if (limitQueryInFlight) return@launch
                if (prev != null &&
                    (now - limitQueryTimeMs < LIMIT_QUERY_MIN_INTERVAL_MS ||
                        prev.distanceTo(location) < LIMIT_QUERY_MIN_DISTANCE_M)
                ) return@launch
                limitQueryInFlight = true
                limitQueryLocation = location
                limitQueryTimeMs = now
            }

            try {
                when (val r = SpeedLimitProvider.fetch(location.latitude, location.longitude, bearing)) {
                    is SpeedLimitProvider.Result.Ok -> {
                        SpeedLimitCache.put(cacheDao, location.latitude, location.longitude, bearing, r)
                        applyLimit(r)
                    }
                    // Sin red o servidor ocupado: se conserva el último valor y se espera antes de insistir
                    SpeedLimitProvider.Result.Failed ->
                        synchronized(limitLock) { limitQueryTimeMs = SystemClock.elapsedRealtime() + LIMIT_FAIL_BACKOFF_MS }
                }
            } finally {
                synchronized(limitLock) { limitQueryInFlight = false }
            }
        }
    }

    /**
     * Android solo manda un fix cuando te has movido al menos 1 m, así que al pararte
     * dejan de llegar y la última velocidad se quedaba en pantalla (8 km/h durante
     * 19 s en la ruta 40). Si hace más de 2,5 s del último fix y ibas despacio, estás
     * parado: velocidad 0. Si ibas rápido es más bien pérdida de señal (túnel): se
     * mantiene unos segundos y después se pone a 0 para no enseñar un dato inventado.
     */
    private fun startStaleSpeedWatchdog() {
        staleSpeedJob?.cancel()
        staleSpeedJob = serviceScope.launch {
            while (isActive) {
                delay(500)
                val loc = lastLocation ?: continue
                if (lastGpsSpeed == 0f) continue
                val ageS = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1e9
                val stopped = ageS > STALE_FIX_STOPPED_S && lastGpsSpeed < STALE_FIX_SLOW_KMH
                val lost = ageS > STALE_FIX_LOST_S
                if (stopped || lost) {
                    lastGpsSpeed = 0f
                    currentSpeed.postValue(0f)
                    updateOverLimit()
                }
            }
        }
    }

    /** Vía sin dato ni estimación: se sigue con el último límite conocido. */
    private fun applyLimit(r: SpeedLimitProvider.Result.Ok) {
        val limit = r.limitKmh ?: return
        knownLimit = limit
        speedLimitEstimated.postValue(r.estimated)
        currentSpeedLimit.postValue(limit)
        updateOverLimit()
    }

    /** Marca el exceso de velocidad y pita al rebasar el límite (y cada 15 s mientras dure). */
    @Synchronized
    private fun updateOverLimit() {
        val limit = knownLimit
        val over = limit > 0 && lastGpsSpeed > limit + OVER_LIMIT_TOLERANCE_KMH
        if (over != overLimit) overSpeedLimit.postValue(over)
        if (over) {
            val now = SystemClock.elapsedRealtime()
            if (!overLimit || now - lastOverAlertMs >= OVER_LIMIT_REPEAT_MS) {
                lastOverAlertMs = now
                beep()
            }
        }
        overLimit = over
    }

    private fun beep() {
        try {
            val tone = toneGenerator
                ?: ToneGenerator(AudioManager.STREAM_MUSIC, 100).also { toneGenerator = it }
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        } catch (e: RuntimeException) {
            Log.w(TAG, "No se pudo reproducir el aviso: ${e.message}")
        }
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

                updateLongitudinalAccel(event.values)

                currentAccel.postValue(accelMagnitude)
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                // Lectura no fiable: se conserva el último lean válido
                calibrationOpen()
                rawLeanAngle = lateralLeanDegrees() ?: return
                autoCalibrateWhenStill(rawLeanAngle)
                val correctedLean = rawLeanAngle - restingAngleOffset
                currentLeanDeg = correctedLean
                currentLean.postValue(abs(correctedLean))
                currentLeanSigned.postValue(correctedLean)
                if (abs(correctedLean) > (maxLean.value ?: 0f)) {
                    maxLean.postValue(abs(correctedLean))
                }
                // Convención: negativo = izquierda, positivo = derecha
                if (correctedLean < 0 && -correctedLean > (maxLeanLeft.value ?: 0f)) {
                    maxLeanLeft.postValue(-correctedLean)
                } else if (correctedLean > 0 && correctedLean > (maxLeanRight.value ?: 0f)) {
                    maxLeanRight.postValue(correctedLean)
                }
            }
        }
    }

    private fun setCalibrationStatus(status: CalibrationStatus) {
        if (calibrationStatus.value != status) calibrationStatus.postValue(status)
    }

    /**
     * ¿Sigue abierta la fase de calibración? Se cierra a los 2 minutos de empezar la
     * ruta; si se cierra sin haber calibrado se avisa y se sigue con el offset
     * guardado de la vez anterior.
     */
    private fun calibrationOpen(): Boolean {
        if (calibratedThisRide || calibPhaseOver) return false
        if (SystemClock.elapsedRealtime() - calibStartMs > CALIB_PHASE_MS) {
            calibPhaseOver = true
            stillN = 0
            setCalibrationStatus(CalibrationStatus.EXPIRED)
            return false
        }
        return true
    }

    /**
     * Fija el cero de inclinación cuando la moto rueda recta entre 5 y 20 km/h con el
     * ángulo estable, durante la fase inicial de la ruta (ver [calibrationOpen]).
     * "Recta" se comprueba con la aceleración lateral, que es la física de la curva:
     * si es casi cero, la moto no está inclinada para tomarla.
     */
    private fun autoCalibrateWhenStill(raw: Float) {
        if (!calibrationOpen()) return
        val rolling = lastGpsSpeed >= CALIB_MIN_SPEED_KMH && lastGpsSpeed < CALIB_MAX_SPEED_KMH
        if (!rolling || lateralAccelAbs > CALIB_MAX_LATERAL_MS2) {
            stillN = 0
            setCalibrationStatus(CalibrationStatus.WAITING)
            return
        }
        setCalibrationStatus(CalibrationStatus.MEASURING)
        val now = SystemClock.elapsedRealtime()
        if (stillN == 0) { stillStartMs = now; stillSum = 0.0; stillSumSq = 0.0 }
        stillN++; stillSum += raw; stillSumSq += raw.toDouble() * raw
        if (now - stillStartMs < CALIB_WINDOW_MS) return

        val mean = stillSum / stillN
        val std = sqrt((stillSumSq / stillN - mean * mean).coerceAtLeast(0.0))
        stillN = 0
        if (std >= CALIB_MAX_STD_DEG || abs(mean) > CALIB_MAX_OFFSET_DEG) return
        calibratedThisRide = true
        setCalibrationStatus(CalibrationStatus.DONE)
        if (abs(mean - restingAngleOffset) > 0.3) {
            Log.i(TAG, "Calibración automática: offset ${fmt(restingAngleOffset)}° → ${fmt(mean.toFloat())}°")
            restingAngleOffset = mean.toFloat()
            getSharedPreferences("mototrack_prefs", Context.MODE_PRIVATE)
                .edit().putFloat(PREF_LEAN_OFFSET, restingAngleOffset).apply()
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
    private fun lateralLeanDegrees(): Float? {
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

        // Móvil fuera del soporte (pantalla casi horizontal, boca abajo, en el
        // bolsillo…): la proyección es ~0 o apunta hacia abajo y atan2 se
        // dispara (-120°, +170° en el test del 24/09). No hay lean fiable.
        if (sqrt(sx * sx + sy * sy) < MIN_SCREEN_VERTICALITY || sy <= 0f) return null

        val lean = Math.toDegrees(atan2(-sx, sy).toDouble()).toFloat()
        return if (abs(lean) <= MAX_PLAUSIBLE_LEAN_DEG) lean else null
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
            append(',').append(currentSpeedLimit.value ?: 0)
            append(String.format(Locale.US, ",%.2f", smoothedAccel))
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
                altitude      = msl.of(location),
                accuracy      = location.accuracy,
                speedKmh      = lastGpsSpeed,
                accelX        = linearAcc[0],
                accelY        = linearAcc[1],
                accelZ        = linearAcc[2],
                accelTotal    = accelMagnitude,
                longAccel     = smoothedAccel,
                leanAngle     = currentLeanDeg,
                bearing       = location.bearing,
                hdop          = if (location.hasAccuracy()) location.accuracy else -1f,
                vdop          = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else -1f,
                satellites    = location.extras?.getInt("satellites") ?: 0,
                altitudeEllipsoid = location.altitude,
                speedLimitKmh = currentSpeedLimit.value ?: 0,
                speedLimitEstimated = speedLimitEstimated.value ?: false
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

/** Estado de la calibración inicial del ángulo de inclinación, para avisar al usuario. */
enum class CalibrationStatus {
    OFF,        // sin ruta en curso
    WAITING,    // a la espera de que el piloto ruede recto entre 5 y 20 km/h
    MEASURING,  // midiendo el cero de inclinación: hay que seguir recto
    DONE,       // calibrado
    EXPIRED     // la fase inicial acabó sin calibrar: se sigue con el offset anterior
}

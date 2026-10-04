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
import kotlin.math.asin
import kotlin.math.sin
import com.mototrack.utils.Compass
import com.mototrack.utils.MslAltitude
import com.mototrack.utils.PlaceTracker
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
        // Nº de calibraciones primarias fundidas en PREF_LEAN_OFFSET desde siempre (todas las
        // rutas, no solo la de hoy): con esto el offset es una media incremental, no el último
        // valor suelto
        private const val PREF_LEAN_COUNT = "resting_lean_offset_count"
        // La R1200RS homologa ~47° de inclinación máxima; más allá es ruido de orientación,
        // no la moto tumbándose (picos vistos de 47,9° y 56,2° que no cuadran ni con la moto
        // ni con el estilo de conducción). El límite tiene que ser ese 47°, no un margen por
        // encima: con 50° el hueco de 3° dejaba pasar ruido (visto en ruta del 29/09: -49,3°
        // en una curva sin frenada ni bache que lo justifique)
        private const val MAX_PLAUSIBLE_LEAN_DEG = 47f
        // El lean real no puede cambiar más rápido que esto (incluido un cambio de signo);
        // un salto mayor en una sola lectura es un fallo puntual del sensor de rotación
        private const val MAX_LEAN_RATE_DEG_S = 150f
        // Tras un salto imposible el sensor de rotación se queda "pegado" un rato: se pone
        // en cuarentena el lean este tiempo en vez de fiarse de la primera lectura que
        // vuelva a parecer razonable (también puede ser ruido decayendo desde el salto)
        private const val LEAN_QUARANTINE_MS = 2_000L
        // Módulo mínimo del "arriba" proyectado en la pantalla (0.5 ≈ pantalla a ≤60° de la vertical)
        private const val MIN_SCREEN_VERTICALITY = 0.5f

        // Autocalibración del cero de inclinación (moto derecha): calibración PRIMARIA en
        // cuanto hay referencia fiable de marcha en recta — no hace falta pararse, la media
        // de muchos metros en recto ya es mejor referencia del cero que cualquier parada
        // (donde casi siempre hay un pie en el suelo o la moto en el caballete). A partir de
        // ahí, el resto de la ruta solo aporta correcciones SECUNDARIAS de bajo peso cada vez
        // que se baja de [STRAIGHT_MIN_SPEED_KMH] (tráfico lento, semáforo, parada real).
        private const val CALIB_MIN_DISTANCE_M = 200f
        // Marcha en recta: rodando >15 km/h, sin fuerza lateral y con el rumbo GPS estable.
        private const val STRAIGHT_MIN_SPEED_KMH = 15f
        private const val STRAIGHT_MAX_LATERAL_MS2 = 0.5f
        private const val STRAIGHT_MAX_BEARING_DELTA_DEG = 4f
        private const val STRAIGHT_MIN_TIME_MS = 3_000L
        private const val STRAIGHT_MAX_FIX_AGE_S = 1.6
        private const val LATERAL_SMOOTHING = 0.02f
        // Una inclinación medida (primaria o secundaria) mayor que esto no es del soporte:
        // se descarta la muestra
        private const val CALIB_MAX_OFFSET_DEG = 15f
        // Si una muestra se aleja del offset vigente más que esto, la moto estaba ladeada
        // (pie en el suelo, caballete) y no vale para calibrar/corregir
        private const val CALIB_MAX_DIFF_FROM_STRAIGHT_DEG = 4f
        // Ventana de la corrección secundaria: hay que mantenerse por debajo de
        // STRAIGHT_MIN_SPEED_KMH con el ángulo estable este tiempo seguido para que cuente
        private const val CALIB_CORR_WINDOW_MS = 3_000L
        private const val CALIB_CORR_MAX_STD_DEG = 1.0
        // Peso de cada corrección secundaria sobre el offset vigente: pequeño a propósito,
        // para que ninguna parada aislada pueda desviar el offset de golpe
        private const val CALIB_CORR_LEARNING_RATE = 0.15f

        // Corrección DINÁMICA: en curva sostenida a velocidad de carretera, atan(v·ω/g) da
        // el lean que un turno coordinado necesitaría para esa velocidad y esa velocidad de
        // guiñada (rumbo GPS) — la física de un avión o un dron para saber su inclinación sin
        // depender de encontrar "la vertical". No le afecta el peralte de la vía, que sí
        // contamina la calibración en recto (ver [trackStraightRiding]).
        private const val DYNAMIC_CORR_MIN_SPEED_KMH = 25f
        // Por debajo de esto el rumbo GPS apenas cambia entre fixes y ω sale puro ruido
        private const val DYNAMIC_CORR_MIN_YAW_RATE_DEG_S = 5f
        private const val DYNAMIC_CORR_MAX_ACCURACY_M = 8f
        private const val DYNAMIC_CORR_WINDOW_MS = 2_000L
        private const val DYNAMIC_CORR_MAX_STD_DEG = 2.0
        // Más lento que la secundaria: una curva real casi nunca es un turno perfectamente
        // coordinado (frenada o aceleración en la curva, viento, corrección del piloto), así
        // que cada ventana individual pesa menos que un tramo parado y quieto
        private const val DYNAMIC_CORR_LEARNING_RATE = 0.05f
        // Suavizado de ω: a la cadencia del GPS, fix a fix sale con demasiado ruido para
        // usarlo directo
        private const val YAW_RATE_SMOOTHING = 0.3f

        // Límite de la vía: no saturar Overpass (uso justo) ni gastar datos de más
        // Suavizado de la aceleración longitudinal (~0,3 s a la frecuencia del acelerómetro)
        private const val ACCEL_SMOOTHING = 0.1f
        // Un bache da un pico aislado de decenas de m/s² que ninguna moto de calle alcanza
        // acelerando o frenando (visto hoy: picos de ±9-10 m/s² ya después de suavizar, es
        // decir con ráfagas mucho mayores en crudo); se descarta antes de que contamine el
        // filtro, igual que con el lean
        private const val MAX_PLAUSIBLE_ACCEL_MS2 = 12f

        // Velocidad "caducada": sin fix nuevo, ver startStaleSpeedWatchdog
        private const val STALE_FIX_STOPPED_S = 2.5
        private const val STALE_FIX_SLOW_KMH = 15f
        private const val STALE_FIX_LOST_S = 8.0
        // Por debajo de esto es ruido y vibración del móvil, no aceleración de marcha
        private const val ACCEL_DEADBAND = 0.4f
        // Por debajo de esta velocidad GPS (a pie, parado) "adelante" no está definido: el
        // móvil va en la mano o el bolsillo y el balanceo no es aceleración de marcha
        private const val ACCEL_MIN_GPS_SPEED_KMH = 8f

        // Altura: se descarta un fix con precisión vertical peor que esto, y el resto se
        // suaviza limitando cuánto puede moverse por fix (a 1 Hz, ~5 m/s ya es mucho)
        private const val ALT_MAX_VERTICAL_ACCURACY_M = 12f
        private const val ALT_MAX_STEP_M = 5.0
        private const val ALT_SMOOTHING = 0.3

        // Con la caché local, la red solo se usa en tramos nuevos: se puede preguntar antes
        private const val LIMIT_QUERY_MIN_DISTANCE_M = 30f
        private const val LIMIT_QUERY_MIN_INTERVAL_MS = 4_000L
        // Si Overpass falla (504, sin datos), esperar antes de insistir
        private const val LIMIT_FAIL_BACKOFF_MS = 10_000L

        // Búsqueda anticipada: mirar la vía por delante para tener el límite ya en la caché
        // al llegar (a 90 km/h, esperar 3 s a Overpass son 75 m de retraso)
        private const val AHEAD_SECONDS = 8.0
        private const val AHEAD_MIN_M = 100.0
        private const val AHEAD_MAX_M = 350.0
        private const val AHEAD_MIN_SPEED_KMH = 10f
        private const val BEARING_MIN_SPEED_KMH = 5f
        private const val AHEAD_MIN_DISTANCE_M = 60f
        private const val AHEAD_MIN_INTERVAL_MS = 5_000L

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
        val compassHeading  = MutableLiveData<Float?>(null)   // rumbo por la brújula del móvil, sin GPS
        val currentPlace    = MutableLiveData<String?>(null)  // urbanización o calle donde está la moto
        val currentPosition = MutableLiveData<DoubleArray?>(null) // [lat, lon, precisión en m] de la última posición
        // true en cuanto se calibra una vez desde que se abrió la app (el mapa del Dashboard se oculta)
        val calibratedSinceAppStart = MutableLiveData(false)
        // false hasta la calibración primaria de ESTA ruta: antes de eso currentLean /
        // currentLeanSigned no se actualizan y no son de fiar (el offset puede venir de una
        // ruta anterior, o ser 0 la primera vez)
        val leanValid = MutableLiveData(false)
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
    // "forward" sin filtrar ni descartar, solo para el log crudo (diagnóstico de picos)
    private var forwardAccelRaw = 0f

    // Consulta del límite de velocidad
    /** Estado de una consulta de límite a Overpass: en curso, cuándo y dónde fue la última. */
    private class LimitSlot {
        var inFlight = false
        var timeMs = 0L
        var loc: Location? = null
    }
    private val hereSlot = LimitSlot()    // el punto en el que estoy
    private val aheadSlot = LimitSlot()   // la vía que tengo por delante
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
    // Nº de calibraciones primarias fundidas en restingAngleOffset desde siempre (persistido)
    private var calibrationCount = 0
    private var rawLeanAngle = 0f
    // Filtro de saltos imposibles del lean (ver plausibleLean)
    private var prevRawLean = Float.NaN
    private var prevLeanEventNs = 0L
    private var leanQuarantineUntilMs = 0L
    // Ventana de la corrección secundaria (moto <15 km/h, a la frecuencia del sensor)
    private var corrStartMs = 0L
    private var corrN = 0
    private var corrSum = 0.0
    private var corrSumSq = 0.0
    // Detector de marcha en recta y ángulo medio rodando recto (referencia del cero)
    @Volatile private var lateralAccelMean = 0f
    @Volatile private var bearingSteady = false
    private var prevBearing = Float.NaN
    private var straightSinceMs = 0L
    private var straightLeanRef = Float.NaN
    private var straightRefSum = 0.0
    private var straightRefN = 0
    // Ya hubo calibración primaria en esta ruta; a partir de ahí solo correcciones secundarias
    private var calibratedThisRide = false
    // Velocidad de guiñada (derivada del rumbo GPS) para la corrección dinámica
    private var yawRateDegS = 0f
    private var prevBearingElapsedNs = 0L
    // Ventana de la corrección dinámica (moto en curva sostenida, a la frecuencia del sensor)
    private var dynCorrStartMs = 0L
    private var dynCorrN = 0
    private var dynCorrSum = 0.0
    private var dynCorrSumSq = 0.0
    val calibrationDone = MutableLiveData<Float>()
    private var lastGpsAlt = 0.0
    // Altura filtrada (NaN hasta el primer fix de la ruta)
    private var altFiltered = Double.NaN
    // Satélites usados en el fix, del GnssStatus (Location.extras no lo trae de forma fiable)
    @Volatile private var satellitesUsed = 0
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satellitesUsed = used
        }
    }

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
        altFiltered = Double.NaN
        calibratedThisRide = false
        leanValid.postValue(false)
        prevRawLean = Float.NaN
        prevLeanEventNs = 0L
        leanQuarantineUntilMs = 0L
        straightSinceMs = 0L
        straightLeanRef = Float.NaN
        straightRefSum = 0.0
        straightRefN = 0
        prevBearing = Float.NaN
        bearingSteady = false
        corrN = 0
        calibrationStatus.postValue(CalibrationStatus.WAITING)
        avgSpeed.postValue(0f)
        longitudinalAccel.postValue(0f)
        hereSlot.loc = null
        aheadSlot.loc = null
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
            // Pedimos 5 Hz sin mínimo de distancia: a 1 Hz y 1 m mínimo, en tráfico lento
            // (10-20 km/h) casi no llegan fixes nuevos y la derivada del rumbo (turno
            // coordinado) sale muy ruidosa. El chip GNSS puede no dar más de 1-2 Hz reales
            // aunque se pida más; hay que comprobar en el CSV la cadencia que entrega de verdad.
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 200L) // pedimos 5 Hz
                .setMinUpdateDistanceMeters(0f)
                // Que el primer fix no espere a ser preciso: sale uno aproximado ya y se afina
                .setWaitForAccurateLocation(false)
                .build()
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.addNmeaListener(msl.nmeaListener, Handler(Looper.getMainLooper()))
            lm.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            Log.e(TAG, "GPS permission denied: ${e.message}")
        }
    }

    private fun unregisterGps() {
        fusedClient.removeLocationUpdates(locationCallback)
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.removeNmeaListener(msl.nmeaListener)
        lm.unregisterGnssStatusCallback(gnssCallback)
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
        // Rumbo estable entre fixes: dato del detector de marcha en recta
        bearingSteady = if (location.hasBearing() && lastGpsSpeed >= STRAIGHT_MIN_SPEED_KMH &&
            !prevBearing.isNaN()
        ) {
            val d = abs(((location.bearing - prevBearing + 540f) % 360f) - 180f)
            d < STRAIGHT_MAX_BEARING_DELTA_DEG
        } else false
        // Velocidad de guiñada para la corrección dinámica: un hueco entre fixes (GPS
        // perdido) o dos fixes casi seguidos (jitter del proveedor) no valen para la derivada
        if (location.hasBearing() && !prevBearing.isNaN() && prevBearingElapsedNs != 0L) {
            val dt = (location.elapsedRealtimeNanos - prevBearingElapsedNs) / 1e9f
            if (dt in 0.05f..2f) {
                val rawYawRate = signedAngularDelta(prevBearing, location.bearing) / dt
                yawRateDegS += YAW_RATE_SMOOTHING * (rawYawRate - yawRateDegS)
            }
        }
        if (location.hasBearing()) prevBearingElapsedNs = location.elapsedRealtimeNanos
        prevBearing = if (location.hasBearing()) location.bearing else Float.NaN
        lastGpsBearing = location.bearing
        lastGpsAlt     = filterAltitude(location)
        lastLocation   = location

        speedSumKmh += lastGpsSpeed
        speedSamples++
        avgSpeed.postValue((speedSumKmh / speedSamples).toFloat())

        currentSpeed.postValue(lastGpsSpeed)
        updateOverLimit()
        currentBearing.postValue(lastGpsBearing)
        currentPosition.postValue(doubleArrayOf(location.latitude, location.longitude, location.accuracy.toDouble()))
        if (sourceOf(location) == "gps") PlaceTracker.update(this, location.latitude, location.longitude)
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
     * Altura sin los picos del GPS: un fix con mala precisión vertical se ignora (se
     * mantiene la última buena) y los demás mueven la altura como mucho [ALT_MAX_STEP_M]
     * por fix, suavizados. El primer fix de la ruta fija el valor de partida.
     */
    private fun filterAltitude(location: Location): Double {
        val raw = msl.of(location)
        if (altFiltered.isNaN()) {
            altFiltered = raw
            return raw
        }
        val poorVertical = location.hasVerticalAccuracy() &&
            location.verticalAccuracyMeters > ALT_MAX_VERTICAL_ACCURACY_M
        if (poorVertical) return altFiltered
        val step = (raw - altFiltered).coerceIn(-ALT_MAX_STEP_M, ALT_MAX_STEP_M)
        altFiltered += ALT_SMOOTHING * step
        return altFiltered
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
        // A pie o parado sin movimiento GPS tampoco: el balanceo del móvil no es marcha
        val forward = if (horiz < MIN_SCREEN_VERTICALITY || lastGpsSpeed < ACCEL_MIN_GPS_SPEED_KMH) 0f else {
            val fx = -zx / horiz
            val fy = -zy / horiz
            val east  = rotMatrix[0] * a[0] + rotMatrix[1] * a[1] + rotMatrix[2] * a[2]
            val north = rotMatrix[3] * a[0] + rotMatrix[4] * a[1] + rotMatrix[5] * a[2]
            // Perpendicular a "adelante": la fuerza de las curvas
            lateral = east * fy - north * fx
            east * fx + north * fy
        }
        // Media CON signo: la vibración del motor se compensa y queda ~0; una curva mantiene
        // la fuerza lateral un buen rato. (La media del valor absoluto nunca baja de ~0,6.)
        lateralAccelMean += LATERAL_SMOOTHING * (lateral - lateralAccelMean)
        forwardAccelRaw = forward
        // Un bache aislado no debe mover el filtro ni un poco: se descarta la muestra entera
        // en vez de dejar que aporte su parte (igual que un salto de lean, pero sin cuarentena:
        // aquí el filtro EWMA ya evita que un pico suelto deje "pegado" el valor)
        if (abs(forward) <= MAX_PLAUSIBLE_ACCEL_MS2) {
            accelFilter += ACCEL_SMOOTHING * (forward - accelFilter)
        }
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
        // El rumbo decide qué vía es la nuestra al cruzar otra (una autovía por debajo o por encima
        // va a 90° y se descarta); el GPS lo da con fiabilidad desde unos 5 km/h
        val bearing = if (location.hasBearing() && lastGpsSpeed >= BEARING_MIN_SPEED_KMH) location.bearing else null

        serviceScope.launch {
            val cacheDao = db.speedLimitCacheDao()

            // 1) Donde estoy: caché (instantánea) y solo si no se conoce, la red
            val cached = SpeedLimitCache.get(cacheDao, location.latitude, location.longitude, bearing)
            if (cached != null) applyLimit(cached)
            else fetchLimit(cacheDao, location, bearing, hereSlot, apply = true, LIMIT_QUERY_MIN_DISTANCE_M, LIMIT_QUERY_MIN_INTERVAL_MS)

            // 2) La vía de delante: si su límite no está en la caché, se pide ya para tenerlo
            //    cuando lleguemos. Sin prisa: la consulta de aquí tiene prioridad.
            if (bearing != null && lastGpsSpeed >= AHEAD_MIN_SPEED_KMH && !hereSlot.inFlight) {
                val metres = (lastGpsSpeed / 3.6 * AHEAD_SECONDS).coerceIn(AHEAD_MIN_M, AHEAD_MAX_M)
                val ahead = pointAhead(location, bearing, metres)
                if (SpeedLimitCache.get(cacheDao, ahead.latitude, ahead.longitude, bearing) == null) {
                    fetchLimit(cacheDao, ahead, bearing, aheadSlot, apply = false, AHEAD_MIN_DISTANCE_M, AHEAD_MIN_INTERVAL_MS)
                }
            }
        }
    }

    /** Punto a [metres] metros de [from] en la dirección [bearing]. */
    private fun pointAhead(from: Location, bearing: Float, metres: Double): Location {
        val d = metres / 6_371_000.0
        val br = Math.toRadians(bearing.toDouble())
        val lat1 = Math.toRadians(from.latitude)
        val lon1 = Math.toRadians(from.longitude)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(br))
        val lon2 = lon1 + atan2(sin(br) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return Location("ahead").apply {
            latitude = Math.toDegrees(lat2)
            longitude = Math.toDegrees(lon2)
        }
    }

    /**
     * Consulta el límite a Overpass en [at] y lo guarda en la caché: en el punto exacto y a
     * lo largo de la vía. Con [apply] lo muestra ya; sin él (búsqueda anticipada) solo lo
     * deja guardado. Pausa mínima entre consultas del mismo hueco y espera si el servidor falla.
     */
    private suspend fun fetchLimit(
        dao: SpeedLimitCacheDao, at: Location, bearing: Float?, slot: LimitSlot,
        apply: Boolean, minDistanceM: Float, minIntervalMs: Long
    ) {
        val now = SystemClock.elapsedRealtime()
        synchronized(limitLock) {
            val prev = slot.loc
            if (slot.inFlight) return
            if (prev != null && (now - slot.timeMs < minIntervalMs || prev.distanceTo(at) < minDistanceM)) return
            slot.inFlight = true
            slot.loc = at
            slot.timeMs = now
        }
        try {
            when (val r = SpeedLimitProvider.fetch(at.latitude, at.longitude, bearing)) {
                is SpeedLimitProvider.Result.Ok -> {
                    SpeedLimitCache.put(dao, at.latitude, at.longitude, bearing, r)
                    SpeedLimitCache.putAlong(dao, at.latitude, at.longitude, r)
                    if (apply) applyLimit(r)
                }
                // Sin red o servidor ocupado: se conserva el último valor y se espera antes de insistir
                SpeedLimitProvider.Result.Failed ->
                    synchronized(limitLock) { slot.timeMs = SystemClock.elapsedRealtime() + LIMIT_FAIL_BACKOFF_MS }
            }
        } finally {
            synchronized(limitLock) { slot.inFlight = false }
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
                Compass.azimuth(rotMatrix)?.let { az ->
                    val prev = compassHeading.value
                    if (prev == null || angularDiff(prev, az) >= 2f) compassHeading.postValue(az)
                }
                // Lectura no fiable, salto imposible o en cuarentena tras uno: se conserva
                // el último lean válido
                val lean = lateralLeanDegrees()?.let { plausibleLean(it, event.timestamp) } ?: return
                rawLeanAngle = lean
                updateCalibration(rawLeanAngle)
                // Antes de la calibración primaria de esta ruta el offset no es de fiar
                // (puede venir de una ruta anterior, o ser 0 la primera vez): no se publica
                // ni se cuenta para los máximos hasta entonces.
                if (calibratedThisRide) {
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
    }

    private fun setCalibrationStatus(status: CalibrationStatus) {
        if (calibrationStatus.value != status) calibrationStatus.postValue(status)
    }

    /**
     * ¿Vamos recto? En marcha a más de 15 km/h con una posición reciente, sin fuerza
     * lateral y con el rumbo GPS estable durante al menos 3 s. Mientras dura, el ángulo
     * medio es la referencia del cero (moto derecha), independiente de la parada.
     *
     * La calibración PRIMARIA se dispara aquí dentro a propósito (no en otro sitio ni en
     * otro instante): así queda garantizado que la primera calibración de la ruta ocurre
     * siempre en marcha, en el mismo tick en que se confirma "vamos recto", y nunca se
     * demora hasta un momento posterior en que ya se podría estar parado.
     */
    private fun trackStraightRiding(raw: Float) {
        val fixAgeS = lastLocation?.let {
            (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1e9
        } ?: Double.MAX_VALUE
        val straight = lastGpsSpeed >= STRAIGHT_MIN_SPEED_KMH && fixAgeS < STRAIGHT_MAX_FIX_AGE_S &&
            abs(lateralAccelMean) < STRAIGHT_MAX_LATERAL_MS2 && bearingSteady
        if (!straight) { straightSinceMs = 0L; return }
        val now = SystemClock.elapsedRealtime()
        if (straightSinceMs == 0L) straightSinceMs = now
        if (now - straightSinceMs >= STRAIGHT_MIN_TIME_MS) {
            // Media de todo el tiempo recto de la ruta: rodando recto hay ruido de varios
            // grados (la dirección se corrige), y así se promedia
            straightRefSum += raw
            straightRefN++
            straightLeanRef = (straightRefSum / straightRefN).toFloat()

            if (!calibratedThisRide && totalDistance * 1000f >= CALIB_MIN_DISTANCE_M &&
                abs(straightLeanRef) <= CALIB_MAX_OFFSET_DEG) {
                applyPrimaryCalibration(straightLeanRef)
            }
        }
    }

    /**
     * Calibración PRIMARIA (ver [trackStraightRiding], que es quien la dispara) y, el
     * resto de la ruta, correcciones SECUNDARIAS al pararse (ver [trackSecondaryCorrection])
     * y DINÁMICAS en curva (ver [trackDynamicCorrection]). Se evalúa en cada lectura del sensor.
     */
    private fun updateCalibration(raw: Float) {
        trackStraightRiding(raw)
        if (calibratedThisRide) {
            trackSecondaryCorrection(raw)
            trackDynamicCorrection(raw)
        } else {
            setCalibrationStatus(CalibrationStatus.WAITING)
        }
    }

    /** Funde [measured] en el offset persistido como media incremental (histórico "de siempre"). */
    private fun applyPrimaryCalibration(measured: Float) {
        val newOffset = if (calibrationCount == 0) measured
            else restingAngleOffset + (measured - restingAngleOffset) / (calibrationCount + 1)
        calibrationCount++
        if (abs(newOffset - restingAngleOffset) > 0.05f) {
            Log.i(TAG, "Calibración primaria: offset ${fmt(restingAngleOffset)}° → ${fmt(newOffset)}° " +
                "(muestra en recto ${fmt(measured)}°, histórico #$calibrationCount)")
        }
        restingAngleOffset = newOffset
        getSharedPreferences("mototrack_prefs", Context.MODE_PRIVATE).edit()
            .putFloat(PREF_LEAN_OFFSET, restingAngleOffset)
            .putInt(PREF_LEAN_COUNT, calibrationCount)
            .apply()
        calibratedThisRide = true
        setCalibrationStatus(CalibrationStatus.DONE)
        calibratedSinceAppStart.postValue(true)
        leanValid.postValue(true)
    }

    /**
     * Correcciones SECUNDARIAS tras la primaria: cada vez que se baja de
     * [STRAIGHT_MIN_SPEED_KMH] (tráfico lento, semáforo, parada real) con el ángulo estable
     * durante [CALIB_CORR_WINDOW_MS], se compara esa media con el offset vigente. Si concuerda
     * se aplica como ajuste de bajo peso ([CALIB_CORR_LEARNING_RATE]); si no (pie en el suelo,
     * caballete), se descarta. A diferencia de la primaria, puede darse varias veces por ruta.
     */
    private fun trackSecondaryCorrection(raw: Float) {
        val slow = lastGpsSpeed < STRAIGHT_MIN_SPEED_KMH
        if (!slow) { corrN = 0; return }
        val now = SystemClock.elapsedRealtime()
        if (corrN == 0) { corrStartMs = now; corrSum = 0.0; corrSumSq = 0.0 }
        corrN++; corrSum += raw; corrSumSq += raw.toDouble() * raw
        if (now - corrStartMs < CALIB_CORR_WINDOW_MS) return

        val mean = corrSum / corrN
        val std = sqrt((corrSumSq / corrN - mean * mean).coerceAtLeast(0.0))
        corrN = 0
        if (std >= CALIB_CORR_MAX_STD_DEG || abs(mean) > CALIB_MAX_OFFSET_DEG) return
        val diff = mean - restingAngleOffset
        if (abs(diff) > CALIB_MAX_DIFF_FROM_STRAIGHT_DEG) {
            Log.i(TAG, "Corrección secundaria descartada: ${fmt(mean.toFloat())}° vs offset ${fmt(restingAngleOffset)}° (moto ladeada)")
            return
        }
        if (abs(diff) < 0.05f) return
        restingAngleOffset += (diff * CALIB_CORR_LEARNING_RATE).toFloat()
        getSharedPreferences("mototrack_prefs", Context.MODE_PRIVATE)
            .edit().putFloat(PREF_LEAN_OFFSET, restingAngleOffset).apply()
        Log.i(TAG, "Corrección secundaria: offset ajustado a ${fmt(restingAngleOffset)}° (muestra ${fmt(mean.toFloat())}°)")
    }

    /**
     * Corrección DINÁMICA: mientras dura una curva sostenida a velocidad de carretera
     * ([DYNAMIC_CORR_MIN_SPEED_KMH], con guiñada mínima [DYNAMIC_CORR_MIN_YAW_RATE_DEG_S]),
     * el ángulo de un turno coordinado da una referencia del offset independiente del
     * giroscopio: si el turno fuera perfecto, [raw] − offset = atan(v·ω/g), así que
     * [raw] − atan(v·ω/g) es una medida directa del offset, sin necesitar "la vertical".
     * A diferencia de la calibración en recto, no le afecta el peralte de la vía.
     */
    private fun trackDynamicCorrection(raw: Float) {
        val accuracyOk = (lastLocation?.accuracy ?: Float.MAX_VALUE) <= DYNAMIC_CORR_MAX_ACCURACY_M
        val turning = lastGpsSpeed >= DYNAMIC_CORR_MIN_SPEED_KMH &&
            abs(yawRateDegS) >= DYNAMIC_CORR_MIN_YAW_RATE_DEG_S && accuracyOk
        if (!turning) { dynCorrN = 0; return }
        val now = SystemClock.elapsedRealtime()
        if (dynCorrN == 0) { dynCorrStartMs = now; dynCorrSum = 0.0; dynCorrSumSq = 0.0 }

        val speedMs = lastGpsSpeed / 3.6
        val yawRateRad = Math.toRadians(yawRateDegS.toDouble())
        val coordinatedLean = Math.toDegrees(atan(speedMs * yawRateRad / 9.81))
        val impliedOffset = raw - coordinatedLean
        dynCorrN++; dynCorrSum += impliedOffset; dynCorrSumSq += impliedOffset * impliedOffset
        if (now - dynCorrStartMs < DYNAMIC_CORR_WINDOW_MS) return

        val mean = dynCorrSum / dynCorrN
        val std = sqrt((dynCorrSumSq / dynCorrN - mean * mean).coerceAtLeast(0.0))
        dynCorrN = 0
        if (std >= DYNAMIC_CORR_MAX_STD_DEG) return
        val diff = mean - restingAngleOffset
        if (abs(diff) > CALIB_MAX_DIFF_FROM_STRAIGHT_DEG) return
        if (abs(diff) < 0.05) return
        restingAngleOffset += (diff * DYNAMIC_CORR_LEARNING_RATE).toFloat()
        getSharedPreferences("mototrack_prefs", Context.MODE_PRIVATE)
            .edit().putFloat(PREF_LEAN_OFFSET, restingAngleOffset).apply()
        Log.i(TAG, "Corrección dinámica (turno coordinado): offset ajustado a ${fmt(restingAngleOffset)}° " +
            "(implícito ${fmt(mean.toFloat())}°, v=${fmt(lastGpsSpeed)} km/h, ω=${fmt(yawRateDegS)}°/s)")
    }

    /**
     * Descarta saltos de [raw] más rápidos de [MAX_LEAN_RATE_DEG_S] (incluye cambios de
     * signo en una sola lectura, físicamente imposibles) y, tras uno, pone el lean en
     * cuarentena [LEAN_QUARANTINE_MS]: el sensor de rotación se queda "pegado" un rato tras
     * un fallo, así que ni el primer valor que vuelva a parecer razonable es de fiar todavía.
     */
    private fun plausibleLean(raw: Float, eventNs: Long): Float? {
        val now = SystemClock.elapsedRealtime()
        if (now < leanQuarantineUntilMs) return null
        if (!prevRawLean.isNaN() && prevLeanEventNs != 0L) {
            val dtS = (eventNs - prevLeanEventNs) / 1e9
            if (dtS > 0) {
                val rate = abs(raw - prevRawLean) / dtS
                if (rate > MAX_LEAN_RATE_DEG_S) {
                    leanQuarantineUntilMs = now + LEAN_QUARANTINE_MS
                    Log.w(TAG, "Lean descartado: salto ${fmt(prevRawLean)}° → ${fmt(raw)}° " +
                        "en ${(dtS * 1000).toInt()} ms (cuarentena ${LEAN_QUARANTINE_MS} ms)")
                    return null
                }
            }
        }
        prevRawLean = raw
        prevLeanEventNs = eventNs
        return raw
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

    /** Diferencia entre dos rumbos en grados (0..180), cruzando el 0/360. */
    private fun angularDiff(a: Float, b: Float) = abs(((a - b + 540f) % 360f) - 180f)

    /** Diferencia firmada entre dos rumbos (-180..180): positivo = giro a la derecha. */
    private fun signedAngularDelta(from: Float, to: Float) = ((to - from + 540f) % 360f) - 180f

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
            append(',').append(if (calibratedThisRide) 1 else 0)
            append(String.format(Locale.US, ",%.3f", forwardAccelRaw))
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
                altitude      = lastGpsAlt,
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
                satellites    = satellitesUsed,
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
        calibrationCount = prefs.getInt(PREF_LEAN_COUNT, 0)
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
    WAITING,    // a la espera de la distancia mínima y de una parada
    MEASURING,  // parado y midiendo el cero de inclinación: mantener la moto derecha
    DONE        // calibrado
}

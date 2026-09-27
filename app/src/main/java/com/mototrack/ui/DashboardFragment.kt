package com.mototrack.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import android.os.Bundle
import android.util.Log
import android.os.SystemClock
import android.view.*
import android.widget.EditText
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Polyline
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.location.Location
import com.mototrack.utils.Daylight
import com.mototrack.utils.HeatTrail
import com.mototrack.utils.NowPlaying
import com.mototrack.utils.RainForecast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.ViewModelProvider
import com.mototrack.R
import com.mototrack.databinding.FragmentDashboardBinding
import com.mototrack.service.CalibrationStatus
import com.mototrack.utils.AltitudeMonitor
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: MainViewModel
    private var altitudeMonitor: AltitudeMonitor? = null
    private var googleMap: GoogleMap? = null

    // Arranque automático de la ruta al detectar movimiento (armado hasta que arranca; se rearma al parar)
    private var autoStartArmed = true
    private var autoStartBlockedUntilMs = 0L
    private var mapCentered = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        // En horizontal el vúmetro de inclinación ocupa 48 dp: sin escala numérica
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.leanMeter.showScale = false
        }
        setupMap(savedInstanceState)
        setupObservers()
        setupButtons()
    }

    /** Señal con el límite; si lo superas, la señal, la velocidad y "km/h" se ponen en rojo. */
    private var lastHeading = Float.NaN

    /**
     * Punto cardinal y grados. En marcha (más de 3 km/h) el rumbo del GPS, que es el más
     * fiable; parado, la brújula del móvil (con el móvil en el soporte, hacia donde mira
     * la moto). Si no hay ninguno se conserva el último, en gris.
     */
    private fun updateHeading() {
        val moving = (viewModel.currentSpeed.value ?: 0f) >= 3f
        val compass = viewModel.compassHeading.value
        val live = when {
            moving -> viewModel.currentBearing.value
            else -> compass
        }
        if (live != null) lastHeading = live
        val h = lastHeading
        binding.tvHeading.text = if (h.isNaN()) "—" else CARDINALS[(((h % 360f) + 22.5f) / 45f).toInt() % 8]
        binding.tvHeadingDeg.text = if (h.isNaN()) "" else String.format("%.0f°", h)
        val color = ContextCompat.getColor(
            requireContext(), if (live != null) R.color.accent_cyan else R.color.text_secondary)
        binding.tvHeading.setTextColor(color)
    }

    private fun updateSpeedLimitSign() {
        val limit = viewModel.currentSpeedLimit.value ?: 0
        val exceeded = viewModel.overSpeedLimit.value ?: false
        // Al arrancar, sin lectura de la vía, la señal no se muestra (nada de un "—" gris)
        binding.speedLimitSign.visibility = if (limit > 0) View.VISIBLE else View.GONE
        binding.speedLimitSign.setEstimated(viewModel.speedLimitEstimated.value ?: false)
        binding.speedLimitSign.setLimit(limit)
        binding.speedLimitSign.setExceeded(exceeded)

        val color = ContextCompat.getColor(
            requireContext(), if (exceeded) R.color.over_limit_red else R.color.text_primary)
        binding.tvSpeed.setTextColor(color)
        binding.tvSpeedUnit.setTextColor(
            if (exceeded) color else ContextCompat.getColor(requireContext(), R.color.text_secondary))
    }

    private fun setupObservers() {

        // Dónde está la moto: urbanización o calle, como en el nombre de la ruta
        viewModel.currentPlace.observe(viewLifecycleOwner) { binding.tvPlace.text = it ?: "" }

        // Dirección de la marcha (N/S/E/O)
        viewModel.currentBearing.observe(viewLifecycleOwner) { updateHeading() }
        viewModel.compassHeading.observe(viewLifecycleOwner) { updateHeading() }

        // Velocidad
        viewModel.currentSpeed.observe(viewLifecycleOwner) { speed ->
            updateHeading()
            updateAltitudeMeter()
            updateDistanceMeter()
            binding.tvSpeed.text = String.format("%.0f", speed)
            updateSpeedLimitSign()
        }

        // Límite de la vía (OpenStreetMap)
        viewModel.currentSpeedLimit.observe(viewLifecycleOwner) { updateSpeedLimitSign() }
        viewModel.speedLimitEstimated.observe(viewLifecycleOwner) { updateSpeedLimitSign() }
        viewModel.overSpeedLimit.observe(viewLifecycleOwner) { updateSpeedLimitSign() }

        // Vúmetro: necesita el signo para saber hacia qué lado encender
        viewModel.currentLeanSigned.observe(viewLifecycleOwner) { lean ->
            binding.leanMeter.setLean(lean)
        }

        // Aceleración
        // Vúmetro vertical: positivo = acelerando, negativo = frenando
        viewModel.longitudinalAccel.observe(viewLifecycleOwner) { accel ->
            binding.tvAccel.text = String.format("%+.1f", accel)
            binding.accelMeter.setAccel(accel)
        }

        // Altura sobre el nivel del mar
        viewModel.currentAltitude.observe(viewLifecycleOwner) { alt ->
            binding.tvAltitude.text = String.format("%.0f m", alt)
            // Azul por debajo del nivel del mar; naranja en el resto
            val color = ContextCompat.getColor(
                requireContext(), if (alt < 0) R.color.below_sea_blue else R.color.accent_orange)
            binding.tvAltitude.setTextColor(color)
            binding.altitudeMeter.setColor(color)
            updateAltitudeMeter()
        }
        viewModel.maxAltitude.observe(viewLifecycleOwner) { updateAltitudeMeter() }
        // La ruta más larga que has hecho es la referencia del vúmetro de distancia
        viewModel.allRoutes.observe(viewLifecycleOwner) { updateDistanceMeter() }

        // Calibración del ángulo: se avisa en la propia tarjeta de inclinación
        viewModel.calibrationStatus.observe(viewLifecycleOwner) { showCalibration(it) }

        // Distancia
        viewModel.distanceKm.observe(viewLifecycleOwner) { km ->
            binding.tvDistance.text = String.format("%.2f km", km)
            updateDistanceMeter()
        }

        // Conteo de puntos
        viewModel.pointCount.observe(viewLifecycleOwner) { count ->
            binding.tvPointCount.text = "$count pts"
        }

        // Stats máximos
        viewModel.maxSpeed.observe(viewLifecycleOwner) { max ->
            binding.tvMaxSpeed.text = String.format("%.0f", max)
        }

        val showMaxLean = {
            binding.leanMeter.setPeaks(viewModel.maxLeanLeft.value ?: 0f, viewModel.maxLeanRight.value ?: 0f)
            binding.tvMaxLean.text = String.format(
                "I %.0f° · D %.0f°",
                viewModel.maxLeanLeft.value ?: 0f,
                viewModel.maxLeanRight.value ?: 0f
            )
        }
        viewModel.maxLeanLeft.observe(viewLifecycleOwner) { showMaxLean() }
        viewModel.maxLeanRight.observe(viewLifecycleOwner) { showMaxLean() }

        // Qué suena en el móvil (iVoox, Apple Music, la SER…)
        NowPlaying.info.observe(viewLifecycleOwner) { updateNowPlaying() }
        NowPlaying.accessGranted.observe(viewLifecycleOwner) { updateNowPlaying() }

        viewModel.avgSpeed.observe(viewLifecycleOwner) { avg ->
            binding.tvAvgSpeed.text = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) String.format("%.0f", avg)
                                      else String.format("Vmed: %.0f km/h", avg)
        }

        // Estado de grabación → actualizar UI
        viewModel.isRecording.observe(viewLifecycleOwner) { recording ->
            updateAltitudeMeter()
            updateDistanceMeter()
            if (recording) {
                binding.btnRecord.text = "⏹ DETENER"
                binding.btnRecord.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.stop_red)
                )
                binding.recordingIndicator.visibility = View.VISIBLE
            } else {
                binding.btnRecord.text = "▶ INICIAR RUTA"
                binding.btnRecord.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.start_green)
                )
                binding.recordingIndicator.visibility = View.GONE
                resetUI()
            }
        }
    }

    /**
     * Último LED = la ruta terminada más larga (o 50 km si aún no hay ninguna). Sin distancia
     * recorrida el vúmetro está vacío; se llena según avanzas, esté la moto en marcha o parada.
     */
    private fun updateDistanceMeter() {
        val km = viewModel.distanceKm.value ?: 0f
        val longest = viewModel.allRoutes.value.orEmpty()
            .filter { it.isCompleted }.maxOfOrNull { it.distanceKm } ?: 0f
        val reference = if (longest > 0f) longest else DEFAULT_DISTANCE_REFERENCE_KM
        binding.distanceMeter.setFraction(if (km <= 0f) 0f else km / reference)
    }

    /**
     * Vúmetro de altura: el primer LED es "bajo el nivel del mar"; del segundo al último,
     * de 0 m a la máxima de la ruta (último LED). Sin lecturas de la ruta no hay máximo con
     * que comparar, así que los LEDs de la escala quedan apagados.
     */
    private fun updateAltitudeMeter() {
        val alt = viewModel.currentAltitude.value ?: return
        val routeMax = viewModel.maxAltitude.value ?: Double.NaN
        binding.altitudeMeter.set(alt, if (routeMax.isNaN()) 0.0 else maxOf(routeMax, alt))
    }

    /** En horizontal no se escribe "INCLINACIÓN" (no aporta): solo los máximos y los avisos de calibración. */
    private fun leanLabelDefault() =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "" else "INCLINACIÓN"

    private fun showCalibration(status: CalibrationStatus) {
        val label = binding.tvLeanLabel
        val (text, colorRes) = when (status) {
            // A la espera (sin distancia mínima o rodando) no se avisa de nada
            CalibrationStatus.WAITING -> leanLabelDefault() to R.color.text_secondary
            CalibrationStatus.MEASURING -> "CALIBRANDO… MOTO DERECHA" to R.color.accent_orange
            CalibrationStatus.DONE -> "CALIBRADO ✓" to R.color.accent_green
            CalibrationStatus.OFF -> leanLabelDefault() to R.color.text_secondary
        }
        label.text = text
        label.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        label.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
        // Mientras se calibra el aviso ocupa toda la fila; el máximo vuelve después
        val calibrating = status == CalibrationStatus.MEASURING
        // En horizontal los máximos van solo en el gráfico (barrita con su número): sin texto
        val hideMaxText = calibrating || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.tvMaxLean.visibility = if (hideMaxText) View.GONE else View.VISIBLE
        binding.tvMaxLeanTitle.visibility = if (hideMaxText) View.GONE else View.VISIBLE

        // "Calibrado" y "sin calibrar" se muestran unos segundos y vuelven a la etiqueta normal
        if (status == CalibrationStatus.DONE) {
            label.postDelayed({
                if (_binding != null && viewModel.calibrationStatus.value == status) {
                    binding.tvLeanLabel.text = leanLabelDefault()
                    binding.tvLeanLabel.visibility = if (leanLabelDefault().isEmpty()) View.GONE else View.VISIBLE
                    binding.tvLeanLabel.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.text_secondary))
                }
            }, 4000)
        }
    }

    private fun setupButtons() {
        binding.btnRecord.setOnClickListener {
            if (viewModel.isRecording.value == true) {
                viewModel.stopTracking()
            } else {
                showStartDialog()
            }
        }
    }

    private fun showStartDialog() {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val defaultName = "Ruta ${sdf.format(Date())}"

        val input = EditText(requireContext()).apply {
            setText(defaultName)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Nueva Ruta")
            .setMessage("Nombre de la ruta:")
            .setView(input)
            .setPositiveButton("Iniciar") { _, _ ->
                val name = input.text.toString().ifBlank { defaultName }
                viewModel.startTracking(name)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Al detener: solo lo instantáneo vuelve a cero (la moto está parada). Distancia, altura,
     * máximos, medias y trazado se quedan a la vista como resumen de la ruta hasta que empiece otra
     * (el servicio los reinicia al arrancar).
     */
    private fun resetUI() {
        binding.tvSpeed.text = "0"
        binding.tvAccel.text = "+0.0"
        binding.accelMeter.reset()
        binding.leanMeter.reset()
    }

    // ── Mapa de la zona (de fondo de las tarjetas de abajo a la derecha) ─────────────
    // El mapa queda siempre de fondo de la columna derecha; los indicadores van encima sin marco.

    private fun setupMap(savedInstanceState: Bundle?) {
        val mapView = binding.dashboardMap ?: return
        mapView.onCreate(savedInstanceState?.getBundle(MAP_STATE))
        mapView.getMapAsync { map ->
            googleMap = map
            map.uiSettings.apply {
                setAllGesturesEnabled(false)
                isZoomControlsEnabled = false
                isMapToolbarEnabled = false
                isMyLocationButtonEnabled = false
            }
            try {
                map.isMyLocationEnabled = true
            } catch (e: SecurityException) { /* sin permiso: se ve el mapa sin el punto azul */ }
            drawTrail()   // trazado ya recorrido (tras un giro de pantalla o con la ruta parada)
            viewModel.currentPosition.value?.let {
                if (it.getOrElse(2) { Double.MAX_VALUE } <= MAP_MAX_ACCURACY_M) moveMap(it)
            }
        }
        viewModel.currentPosition.observe(viewLifecycleOwner) {
            it?.let {
                // Hasta el primer fix preciso el mapa ni se mueve ni se muestra
                if (mapCentered || it.getOrElse(2) { Double.MAX_VALUE } <= MAP_MAX_ACCURACY_M) moveMap(it)
                if (viewModel.isRecording.value == true) addTrailPoint(it, viewModel.currentSpeed.value ?: 0f)
                refreshRain(it)
            }
        }
    }

    // Estilo del mapa según la luz: normal de Google de día, oscuro de noche (null = aún sin fijar)
    private var mapNight: Boolean? = null

    private fun applyMapStyle(map: GoogleMap, p: DoubleArray) {
        val night = Daylight.isNight(p[0], p[1])
        if (night == mapNight) return
        mapNight = night
        try {
            map.setMapStyle(
                if (night) MapStyleOptions.loadRawResourceStyle(requireContext(), R.raw.map_dark) else null
            )
        } catch (e: Exception) { /* estilo no disponible: mapa normal */ }
    }

    private fun moveMap(p: DoubleArray) {
        val map = googleMap ?: return
        applyMapStyle(map, p)
        val camera = CameraUpdateFactory.newLatLngZoom(LatLng(p[0], p[1]), MAP_ZOOM)
        if (!mapCentered) {
            map.moveCamera(camera); mapCentered = true
            revealMap()
        } else map.animateCamera(camera)
    }

    /** Quita la cortina con un fundido, dando un momento a que se pinten los tiles de la zona. */
    private fun revealMap() {
        val cover = _binding?.mapCover ?: return
        cover.postDelayed({
            _binding?.mapCover?.animate()?.alpha(0f)?.setDuration(MAP_REVEAL_FADE_MS)
                ?.withEndAction { _binding?.mapCover?.visibility = View.GONE }?.start()
        }, MAP_REVEAL_DELAY_MS)
    }

    // ── ¿Va a llover? ──────────────────────────────────────────────────────────────
    // Previsión de las próximas horas en la posición actual; se renueva cada 15 min con el
    // Dashboard a la vista y se muestra bajo el nombre del lugar.

    private var lastRainFetchMs = 0L
    private var rainFetching = false

    private fun refreshRain(p: DoubleArray) {
        val now = SystemClock.elapsedRealtime()
        if (rainFetching || (lastRainFetchMs != 0L && now - lastRainFetchMs < RAIN_REFRESH_MS)) return
        rainFetching = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { RainForecast.fetch(p[0], p[1]) }
            rainFetching = false
            // Si falla, se conserva lo que hubiera y se reintenta con la siguiente posición
            if (result is RainForecast.Result.Ok) {
                lastRainFetchMs = SystemClock.elapsedRealtime()
                showRain(result)
            }
        }
    }

    private fun showRain(r: RainForecast.Result.Ok) {
        val tv = _binding?.tvRain ?: return
        val (text, icon, color) = when {
            r.raining -> Triple("Lloviendo ahora", R.drawable.ic_weather_rain, R.color.accent_cyan)
            r.hoursToRain == 0 -> Triple("Lluvia en esta hora · ${r.maxProbPct} %", R.drawable.ic_weather_rain, R.color.accent_cyan)
            r.hoursToRain != null -> Triple("Lluvia en ${r.hoursToRain} h · ${r.maxProbPct} %", R.drawable.ic_weather_rain, R.color.accent_cyan)
            else -> Triple("Sin lluvia prevista (3 h)", R.drawable.ic_weather_sun, R.color.text_secondary)
        }
        val tint = ContextCompat.getColor(requireContext(), color)
        // Icono de una sola tinta, del mismo color que el texto
        val drawable = ContextCompat.getDrawable(requireContext(), icon)?.mutate()?.apply { setTint(tint) }
        tv.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
        tv.text = text
        tv.setTextColor(tint)
        tv.visibility = View.VISIBLE
    }

    // ── Trazado en vivo con mapa de calor ──────────────────────────────────────────
    // Mientras se graba, la ruta se dibuja sobre el mapa de verde (lento) a rojo (rápido)
    // respecto a la velocidad máxima de la ruta (mínimo 5 km/h, para que a pie también se vea).

    private val trailPoints get() = viewModel.trailPoints
    private val trailSpeeds get() = viewModel.trailSpeeds
    private var trailLines: List<Polyline> = emptyList()
    private var trailRedrawPending = false

    private fun addTrailPoint(p: DoubleArray, kmh: Float) {
        val pos = LatLng(p[0], p[1])
        trailPoints.lastOrNull()?.let { last ->
            val d = FloatArray(1)
            Location.distanceBetween(last.latitude, last.longitude, pos.latitude, pos.longitude, d)
            if (d[0] < TRAIL_MIN_STEP_M) return
        }
        trailPoints.add(pos)
        trailSpeeds.add(kmh)
        if (!trailRedrawPending) {
            trailRedrawPending = true
            _binding?.root?.postDelayed({ trailRedrawPending = false; drawTrail() }, TRAIL_REDRAW_MS)
        }
    }

    private fun drawTrail() {
        val map = googleMap ?: return
        trailLines.forEach { it.remove() }
        trailLines = if (trailPoints.size < 2) emptyList()
        else HeatTrail.draw(map, trailPoints, trailSpeeds, maxOf(trailSpeeds.max(), 5f))
    }

    private fun clearTrail() {
        trailLines.forEach { it.remove() }
        trailLines = emptyList()
        trailPoints.clear()
        trailSpeeds.clear()
    }

    /** Al volver al Dashboard con una ruta en curso, recupera de la base de datos lo ya recorrido. */
    private fun restoreTrail() {
        val routeId = viewModel.currentRouteId.value ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = viewModel.routePoints(routeId)
            if (trailPoints.size > 1) return@launch
            trailPoints.clear(); trailSpeeds.clear()
            saved.filter { it.latitude != 0.0 || it.longitude != 0.0 }
                .forEach { addTrailPoint(doubleArrayOf(it.latitude, it.longitude), it.speedKmh) }
        }
    }

    // ── Inicio automático de la ruta ───────────────────────────────────────────────
    // Con el Dashboard a la vista y sin grabar, la ruta arranca sola en cuanto llega cualquier
    // señal de movimiento desde parado (GPS, aceleración o inclinación). Un pop-up avisa al
    // usuario (no pide nada). Al arrancar se desarma y se rearma cuando la moto se para; tras
    // detener una ruta hay una pausa, para que manipular el móvil no la reinicie.

    private fun onIdleSpeed(kmh: Float) {
        if (kmh < AUTO_REARM_KMH) autoStartArmed = true
    }

    private fun onMotionDetected(reason: String) {
        if (viewModel.isRecording.value == true || !autoStartArmed) return
        if (SystemClock.elapsedRealtime() < autoStartBlockedUntilMs) return
        autoStartArmed = false
        Log.i("Dashboard", "Movimiento detectado ($reason): la ruta arranca sola")
        startRouteAutomatically()
    }

    /** Pop-up informativo (no pide nada): aparece con un fundido y se va solo a los 4 s. */
    private fun showAutoStartPopup() {
        _binding?.tvAutoStartPopup?.let { popup ->
            popup.alpha = 0f
            popup.visibility = View.VISIBLE
            popup.animate().alpha(1f).setDuration(250).start()
            popup.postDelayed({
                _binding?.tvAutoStartPopup?.animate()?.alpha(0f)?.setDuration(400)?.withEndAction {
                    _binding?.tvAutoStartPopup?.visibility = View.GONE
                }?.start()
            }, AUTO_START_POPUP_MS)
        }
    }

    private fun startRouteAutomatically() {
        // Mismo nombre por defecto que propone el diálogo manual: al terminar se renombra con los lugares
        val name = "Ruta ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())}"
        viewModel.startTracking(name)
        showAutoStartPopup()
    }

    // La altura se mantiene al día con el Dashboard a la vista y sin grabar;
    // grabando la da el servicio, y fuera del Dashboard no se gasta GPS.
    override fun onStart() {
        super.onStart()
        binding.dashboardMap?.onStart()
        NowPlaying.start(requireContext())
        val monitor = altitudeMonitor ?: AltitudeMonitor(requireContext()).also { altitudeMonitor = it }
        monitor.onSpeed = ::onIdleSpeed
        monitor.onMotion = ::onMotionDetected
        viewModel.isRecording.observe(viewLifecycleOwner) { recording ->
            // El trazado de la ruta terminada se queda en el mapa hasta que empieza otra
            if (recording) {
                val routeId = viewModel.currentRouteId.value
                if (viewModel.trailRouteId != routeId) {
                    // Ruta nueva: fuera el trazado anterior. Si es la misma (giro de pantalla), se conserva
                    clearTrail()
                    viewModel.trailRouteId = routeId
                    restoreTrail()
                } else drawTrail()
            }
            if (recording) monitor.stop() else {
                // Ruta detenida: pausa antes de poder volver a arrancar sola
                autoStartBlockedUntilMs = SystemClock.elapsedRealtime() + AUTO_START_COOLDOWN_MS
                monitor.start()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        binding.dashboardMap?.onStop()
        altitudeMonitor?.stop()
        NowPlaying.stop()
    }

    /**
     * "♪ Título — Artista · App" cuando algo suena. Sin el acceso a notificaciones (que Android
     * exige para leer otras apps) se ofrece activarlo, una sola vez.
     */
    private fun updateNowPlaying() {
        val tv = _binding?.tvNowPlaying ?: return
        val np = NowPlaying.info.value
        val prefs = requireContext().getSharedPreferences("mototrack_prefs", Context.MODE_PRIVATE)
        when {
            np != null -> {
                tv.text = "♪ " + np.title + (np.artist?.let { " — $it" } ?: "") + " · " + np.app
                // Al pulsar: se despliega la barra, con los controles de música del sistema
                tv.setOnClickListener { NowPlaying.expandNotificationShade(requireContext()) }
                tv.visibility = View.VISIBLE
            }
            NowPlaying.accessGranted.value != true && !prefs.getBoolean(KEY_MUSIC_HINT_SEEN, false) -> {
                tv.text = "♪ ¿Qué suena? Toca para activar el acceso"
                tv.setOnClickListener {
                    prefs.edit().putBoolean(KEY_MUSIC_HINT_SEEN, true).apply()
                    tv.visibility = View.GONE
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                tv.visibility = View.VISIBLE
            }
            else -> tv.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        binding.dashboardMap?.onResume()
    }

    override fun onPause() {
        binding.dashboardMap?.onPause()
        super.onPause()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        _binding?.dashboardMap?.onLowMemory()
    }

    override fun onDestroyView() {
        binding.dashboardMap?.onDestroy()
        googleMap = null
        trailLines = emptyList()
        mapCentered = false
        mapNight = null
        super.onDestroyView()
        _binding = null
    }

    private companion object {

        // Referencia del vúmetro de distancia mientras no haya ninguna ruta terminada
        const val DEFAULT_DISTANCE_REFERENCE_KM = 50f

        private const val MAP_STATE = "dashboard_map_state"
        private const val KEY_MUSIC_HINT_SEEN = "music_hint_seen"
        private const val MAP_ZOOM = 16f
        // Fix más preciso que esto = GPS de verdad (no una posición por red de cientos de metros)
        private const val MAP_MAX_ACCURACY_M = 25.0
        private const val MAP_REVEAL_DELAY_MS = 800L
        private const val MAP_REVEAL_FADE_MS = 400L
        private const val RAIN_REFRESH_MS = 15 * 60_000L
        private const val TRAIL_MIN_STEP_M = 4f
        private const val TRAIL_REDRAW_MS = 1_000L
        private const val AUTO_REARM_KMH = 2f
        private const val AUTO_START_COOLDOWN_MS = 60_000L
        private const val AUTO_START_POPUP_MS = 4_000L

        // Ocho puntos, con O de Oeste
        val CARDINALS = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
    }
}

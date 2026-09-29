package com.mototrack.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import kotlin.math.abs
import kotlin.math.ceil
import com.mototrack.utils.AccelEventCounter
import com.mototrack.utils.CurveCounter
import com.mototrack.utils.DrivingStyle
import com.mototrack.utils.HeatTrail
import com.mototrack.R
import com.mototrack.data.RoutePoint
import com.mototrack.service.SensorLogger
import com.mototrack.databinding.FragmentRouteDetailBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class RouteDetailFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentRouteDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: MainViewModel
    private val args: RouteDetailFragmentArgs by navArgs()

    // El mapa y los puntos llegan por caminos distintos y en orden impredecible
    // (el mapa vía getMapAsync, los puntos vía Room/LiveData). Guardamos ambos
    // y pintamos cuando estén los dos. Se anulan en onDestroyView.
    private var googleMap: GoogleMap? = null
    private var routePoints: List<RoutePoint>? = null
    private var routeDistanceKm = 0f

    // Selección en las gráficas: se refleja en todas y en un marcador del mapa
    private var selectionMarker: Marker? = null
    private var syncingSelection = false
    private var charts: List<LineChart> = emptyList()

    // Ciclo de vida de un Fragment (a diferencia de una página Ionic, que vive
    // mientras esté en el stack): la *vista* se destruye al navegar hacia otra
    // pantalla (onDestroyView) aunque la instancia del Fragment siga viva en el
    // back stack, y se recrea (onCreateView) al volver. Por eso todo lo que toca
    // vistas se crea en onCreateView/onViewCreated y se libera en onDestroyView.
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRouteDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        setupMap()
        loadRoute()
        setupExportButton()
        setupCsvExportButton()
    }

    // ── Mapa ──────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap() {
        // El SupportMapFragment está declarado en el XML dentro de este Fragment,
        // así que es un fragment *hijo*: se busca en childFragmentManager, no en
        // el de la Activity. Su ciclo de vida queda atado al nuestro.
        val mapFragment = childFragmentManager
            .findFragmentById(R.id.map_container) as SupportMapFragment

        // getMapAsync NO devuelve el mapa: Google Play Services lo inicializa en
        // segundo plano (carga de tiles, GL, etc.) y llama a onMapReady más tarde
        // en el hilo principal. Es el equivalente a esperar una Promise, pero con
        // callback. Hasta entonces no hay GoogleMap sobre el que dibujar.
        mapFragment.getMapAsync(this)

        // Dentro de un ScrollView, los arrastres verticales sobre el mapa los
        // intercepta el ScrollView. Mientras el dedo esté en el mapa le pedimos
        // que no intercepte; devolvemos false para que el evento llegue al mapa.
        binding.mapTouchOverlay.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    binding.scrollDetail.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    binding.scrollDetail.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    override fun onMapReady(map: GoogleMap) {
        // Al ser asíncrono, este callback podría llegar cuando la vista ya se
        // destruyó (el usuario volvió atrás muy rápido). Sin vista, no pintamos.
        if (_binding == null) return

        googleMap = map.apply {
            uiSettings.isZoomControlsEnabled = true
            uiSettings.isMapToolbarEnabled = false
        }
        renderRouteOnMap()
    }

    /** Pinta la ruta si ya tenemos mapa y puntos; si falta uno, no hace nada. */
    private fun renderRouteOnMap() {
        val map = googleMap ?: return
        val points = routePoints ?: return

        map.clear()
        selectionMarker = null

        // Descartamos puntos sin fix GPS válido (0,0 en el golfo de Guinea).
        val latLngs = points
            .filter { it.latitude != 0.0 || it.longitude != 0.0 }
            .map { LatLng(it.latitude, it.longitude) }

        binding.tvMapEmpty.visibility = if (latLngs.isEmpty()) View.VISIBLE else View.GONE

        when {
            // Ruta sin puntos: mapa vacío centrado en la ubicación por defecto
            latLngs.isEmpty() -> {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_LOCATION, DEFAULT_ZOOM))
            }

            // Un solo punto (o todos iguales): no hay línea que trazar
            latLngs.distinct().size == 1 -> {
                map.addMarker(MarkerOptions().position(latLngs.first()).title("Inicio"))
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLngs.first(), SINGLE_POINT_ZOOM))
            }

            else -> {
                drawHeatLine(map, points)
                map.addMarker(
                    MarkerOptions().position(latLngs.first()).title("Inicio")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                )
                map.addMarker(
                    MarkerOptions().position(latLngs.last()).title("Fin")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                )

                val bounds = LatLngBounds.builder().apply { latLngs.forEach(::include) }.build()
                // newLatLngBounds(bounds, padding) lanza excepción si el mapa aún
                // no tiene tamaño. doOnLayout se ejecuta ya si está medido, o tras
                // el primer layout si no.
                binding.mapContainer.doOnLayout {
                    googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, MAP_PADDING_PX))
                }
            }
        }
    }

    // ── Datos ─────────────────────────────────────────────────────────────────

    private fun loadRoute() {
        // Observamos con viewLifecycleOwner y no con `this`: el Fragment sobrevive
        // en el back stack sin vista, y si observáramos con `this` cada vuelta a
        // la pantalla añadiría un observer más (duplicados) y alguno podría
        // escribir en un binding ya nulo -> crash. viewLifecycleOwner se destruye
        // con la vista y da de baja los observers automáticamente (similar a
        // hacer unsubscribe en ionViewWillLeave, pero sin tener que acordarse).
        viewModel.allRoutes.observe(viewLifecycleOwner) { routes ->
            val route = routes.find { it.id == args.routeId } ?: return@observe

            // Cabecera
            binding.tvRouteName.text = route.name
            binding.tvDistance.text  = String.format("%.2f km", route.distanceKm)
            binding.tvMaxSpeed.text  = String.format("%.0f km/h", route.maxSpeedKmh)
            binding.tvAvgSpeed.text  = String.format("%.0f km/h", route.avgSpeedKmh)
            binding.tvSpeedChartMax.text = String.format("▲ MÁXIMA · %.0f km/h", route.maxSpeedKmh)
            binding.tvSpeedChartAvg.text = String.format("MEDIA · %.0f km/h", route.avgSpeedKmh)
            // En el gráfico la izquierda va arriba y la derecha abajo (setupLeanChart)
            binding.tvLeanChartLeft.text = String.format("▲ IZQUIERDA · %.0f°", route.maxLeanLeft)
            binding.tvLeanChartRight.text = String.format("▼ DERECHA · %.0f°", route.maxLeanRight)
            binding.tvMaxLean.text   = String.format("I %.1f° · D %.1f°", route.maxLeanLeft, route.maxLeanRight)
            binding.tvMaxAccel.text  = String.format("%.2f m/s²", route.maxAcceleration)
            routeDistanceKm = route.distanceKm
            showDrivingStyle()

            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            binding.tvDate.text = sdf.format(Date(route.startTime)) +
                if (route.motoName.isNotBlank()) " · ${route.motoName}" else ""

            if (route.endTime > 0) {
                val ms = route.endTime - route.startTime
                binding.tvDuration.text = String.format(
                    "%d:%02d min",
                    TimeUnit.MILLISECONDS.toMinutes(ms),
                    TimeUnit.MILLISECONDS.toSeconds(ms) % 60
                )
            }
        }

        viewModel.getPointsForRoute(args.routeId).observe(viewLifecycleOwner) { points ->
            routePoints = points
            val curves = CurveCounter.count(points)
            binding.tvCurves.text = String.format("%d (I %d · D %d)", curves.total, curves.left, curves.right)

            showDrivingStyle()

            // Gráficas
            if (points.isNotEmpty()) setupCharts(points)

            // Mapa (si onMapReady aún no llegó, se pintará desde allí)
            renderRouteOnMap()
        }
    }

    /**
     * Estilo de conducción y puntuación. Necesita los puntos y los km de la ruta, que llegan por
     * observers distintos: se llama desde los dos y se pinta cuando ya están los dos.
     */
    private fun showDrivingStyle() {
        val points = routePoints ?: return
        if (routeDistanceKm <= 0f) return
        val score = DrivingStyle.score(points, routeDistanceKm)
        val tv = binding.tvDrivingStyle
        if (score == null) {
            tv.text = "—"
            tv.setTextColor(Color.parseColor("#888888"))
            return
        }
        tv.text = score.text
        tv.setTextColor(Color.parseColor(score.style.colorHex))
    }

    /** Manchas de calor de fondo más la línea de colores por velocidad; la leyenda da la máxima de la ruta. */
    private fun drawHeatLine(map: GoogleMap, points: List<RoutePoint>) {
        val valid = points.filter { it.latitude != 0.0 || it.longitude != 0.0 }
        val maxSpeed = valid.maxOfOrNull { it.speedKmh }?.takeIf { it > 1f } ?: 1f
        binding.heatLegend.visibility = View.VISIBLE
        binding.tvHeatMax.text = String.format("%.0f km/h", maxSpeed)
        val latLngs = valid.map { LatLng(it.latitude, it.longitude) }
        val speeds = valid.map { it.speedKmh }
        HeatTrail.drawHeatmap(map, latLngs, speeds)          // manchas de fondo
        HeatTrail.draw(map, latLngs, speeds, maxSpeed, overBlobs = true)       // y encima la línea de colores
    }

    private fun setupCharts(points: List<RoutePoint>) {
        val t0 = points.first().timestamp
        val time = { i: Int ->
            val s = ((points[i.coerceIn(0, points.size - 1)].timestamp - t0) / 1000).toInt()
            String.format("%d:%02d", s / 60, s % 60)
        }
        // La altitud GPS es ruidosa: media móvil corta para que el perfil se lea
        val altitude = altitudeProfile(points)
        val elevation = movingAverage(altitude.values, 3)
        // Máxima y mínima del perfil que se dibuja: sin los picos sueltos de la altitud GPS
        binding.tvElevationMax.text = String.format("▲ MÁXIMA · %.0f m", elevation.max())
        binding.tvElevationMin.text = String.format("▼ MÍNIMA · %.0f m", elevation.min())
        // La velocidad real sube y baja cada pocos segundos (curvas, frenadas) y a 1 muestra/s el
        // gráfico parecía un electro: media de unos 9 s. El pico dibujado queda algo por debajo de
        // la velocidad máxima de la ficha, que sale de las muestras sin suavizar
        val speed = movingAverage(points.map { it.speedKmh }, SPEED_SMOOTHING_RADIUS)

        // Orden fijo: velocidad, ángulo lateral, altura del terreno
        charts = listOf(
            setupChart(binding.chartSpeed, speed, "Velocidad", "km/h", "%.0f", "#00BCD4", time),
            setupLeanChart(points, time),
            setupChart(binding.chartElevation, elevation, "Altura", "m", "%.0f", "#B39DDB", time)
        )
        setupAccelLevelsChart(points)
        showEstimatedAltitude(elevation, altitude.estimatedUntil, points, time)
    }

    /**
     * Altura de la ruta. Al empezar, el sistema repite la última altitud que conocía durante un
     * minuto o dos (se ha visto de 69 a 106 s en las rutas de casa al colegio, con la moto ya
     * rodando y una precisión vertical de 1-3 m que no era cierta) hasta que hay una altura GPS
     * real. Ese tramo inicial se estima interpolando por distancia recorrida entre la altura de
     * salida y la primera real. Si la moto estuvo parada, la altura constante es legítima y no se
     * toca. [estimatedUntil] es el nº de puntos estimados (0 = ninguno).
     */
    private class AltitudeProfile(val values: List<Float>, val estimatedUntil: Int)

    private fun altitudeProfile(points: List<RoutePoint>): AltitudeProfile {
        // Se parte de la altura GPS cruda (elipsoide + geoide) y no de la guardada, que el filtro
        // del servicio suavizaba con retraso: la primera altura real llega de golpe (54 m de salto
        // en una ruta) y el filtro la convertía en una rampa de otros 40 s. El geoide (MSL menos
        // elipsoide) es casi constante: la mediana de la segunda mitad, ya asentado el filtro.
        val geoid = points.drop(points.size / 2).map { it.altitude - it.altitudeEllipsoid }
            .sorted().let { it[it.size / 2] }
        // Un fix con mala precisión vertical arrastra el último bueno, como en el servicio
        var last = (points.first().altitudeEllipsoid + geoid).toFloat()
        val raw = points.map { p ->
            if (p.vdop > POOR_VERTICAL_ACCURACY_M) last
            else { last = (p.altitudeEllipsoid + geoid).toFloat(); last }
        }
        val startRaw = points.first().altitudeEllipsoid
        val end = points.indexOfFirst { abs(it.altitudeEllipsoid - startRaw) > FROZEN_ALT_TOLERANCE_M }
        if (end < FROZEN_ALT_MIN_POINTS) return AltitudeProfile(raw, 0)
        // Distancia recorrida en el tramo congelado (sin contar puntos sin posición)
        val cum = FloatArray(end + 1)
        val result = FloatArray(1)
        for (i in 1..end) {
            val a = points[i - 1]
            val b = points[i]
            val valid = (a.latitude != 0.0 || a.longitude != 0.0) && (b.latitude != 0.0 || b.longitude != 0.0)
            if (valid) Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
            cum[i] = cum[i - 1] + if (valid) result[0] else 0f
        }
        if (cum[end] < FROZEN_ALT_MIN_DISTANCE_M) return AltitudeProfile(raw, 0)   // parado: es real
        val estimated = raw.toMutableList()
        for (i in 0 until end) estimated[i] = raw[0] + (raw[end] - raw[0]) * (cum[i] / cum[end])
        return AltitudeProfile(estimated, end)
    }

    /**
     * Dibuja el tramo de altura estimado en discontinuo y más apagado, y lo avisa debajo del
     * título: es una estimación, no una lectura.
     */
    private fun showEstimatedAltitude(
        elevation: List<Float>, estimatedUntil: Int, points: List<RoutePoint>, time: (Int) -> String
    ) {
        val note = binding.tvElevationNote
        if (estimatedUntil <= 0) { note.visibility = View.GONE; return }
        val chart = binding.chartElevation
        val data = chart.data ?: return
        val real = data.getDataSetByIndex(0) as? LineDataSet ?: return
        repeat(estimatedUntil) { real.removeFirst() }   // el sólido empieza donde hay lectura real
        val color = Color.parseColor("#B39DDB")
        val estimated = LineDataSet(
            (0..estimatedUntil).map { Entry(it.toFloat(), elevation[it]) }, "Estimada"
        ).apply {
            this.color = color
            setDrawCircles(false)
            lineWidth = 2f
            enableDashedLine(14f, 9f, 0f)
            setDrawFilled(true)
            fillColor = color
            fillAlpha = 30
            setDrawValues(false)
        }
        data.addDataSet(estimated)
        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.invalidate()
        note.text = "Trazo discontinuo: altura estimada. El GPS no dio altura real en los primeros ${time(estimatedUntil)}."
        note.visibility = View.VISIBLE
    }

    /**
     * Inclinación lateral. Hasta la primera calibración de la ruta (más de 200 m en recto) la
     * inclinación guardada es 0 y no es un dato: ese tramo inicial no se dibuja, en vez de pintar
     * una recta plana que parece conducción en línea recta. El eje sigue empezando en 0:00, como
     * el resto de gráficos, y el hueco se ve como un hueco. Suavizado con menos ventana que la
     * velocidad: una curva dura pocos segundos y con más media se aplanaría; los máximos a
     * izquierda y derecha se indican aparte, sin suavizar.
     */
    private fun setupLeanChart(points: List<RoutePoint>, time: (Int) -> String): LineChart {
        val chart = binding.chartLean
        val first = points.indexOfFirst { it.leanAngle != 0f }
        if (first < 0) {
            chart.clear()
            chart.setNoDataText("Sin inclinación válida en esta ruta")
            chart.setNoDataTextColor(Color.parseColor("#888888"))
            chart.invalidate()
            return chart
        }
        val lean = movingAverage(points.drop(first).map { it.leanAngle }, LEAN_SMOOTHING_RADIUS)
        setupChart(chart, lean, "Ángulo lateral", "°", "%.1f", "#FF5722", time, startIndex = first, invertY = true)
        chart.xAxis.axisMinimum = 0f
        chart.xAxis.axisMaximum = (points.size - 1).toFloat()
        // El 0 (moto nivelada) marcado como un horizonte: línea blanca gruesa, detrás de la traza,
        // y en el centro del gráfico: eje simétrico, con el mismo margen a izquierda y a derecha
        // (redondeado a 5° para no dejar margen de sobra: la traza aprovecha toda la altura)
        val range = ceil(lean.maxOf { abs(it) } / 5f).coerceAtLeast(1f) * 5f
        chart.axisLeft.apply {
            axisMinimum = -range
            axisMaximum = range
            removeAllLimitLines()
            addLimitLine(LimitLine(0f).apply {
                lineWidth = 3f
                lineColor = Color.WHITE
            })
            setDrawLimitLinesBehindData(true)
        }
        chart.invalidate()
        return chart
    }

    /** Media móvil centrada con [radius] muestras a cada lado (en los bordes, las que haya). */
    private fun movingAverage(values: List<Float>, radius: Int): List<Float> =
        values.indices.map { i ->
            values.subList(maxOf(0, i - radius), minOf(values.lastIndex, i + radius) + 1).average().toFloat()
        }

    /**
     * Cuántas aceleraciones y frenadas hubo de cada nivel (los del vúmetro): por cada nivel, una
     * barra de aceleración hacia arriba y otra de frenada hacia abajo, con el color de su LED y
     * el conteo junto a la barra. Las rutas grabadas antes de guardar la aceleración (todo a 0)
     * no tienen dato: se avisa en vez de dibujar un gráfico vacío.
     */
    private fun setupAccelLevelsChart(points: List<RoutePoint>) {
        val chart = binding.chartAccelLevels
        if (points.all { it.longAccel == 0f }) {
            chart.clear()
            chart.setNoDataText("Sin aceleración: ruta grabada antes de guardarla")
            chart.setNoDataTextColor(Color.parseColor("#888888"))
            chart.invalidate()
            binding.tvAccelEventsUp.text = ""
            binding.tvAccelEventsDown.text = ""
            return
        }
        val counts = AccelEventCounter.count(points)
        binding.tvAccelEventsUp.text = "▲ ACELERACIONES · ${counts.totalAccel}"
        binding.tvAccelEventsDown.text = "▼ FRENADAS · ${counts.totalBrake}"

        val levelColors = listOf(
            Color.parseColor("#8BC34A"), Color.parseColor("#FFC107"),
            Color.parseColor("#FF5722"), Color.parseColor("#F44336"))   // como los LEDs del vúmetro
        val levelNames = arrayOf("Leve", "Normal", "Fuerte", "Muy fuerte")
        val levels = 0 until AccelEventCounter.LEVELS

        // Las frenadas van en negativo (hacia abajo); las dos series comparten posición en X
        fun dataSet(values: List<Float>, label: String) =
            BarDataSet(levels.map { BarEntry(it.toFloat(), values[it]) }, label).apply {
                colors = levelColors
                valueTextColor = Color.WHITE
                valueTextSize = 12f
                valueFormatter = object : ValueFormatter() {
                    override fun getBarLabel(barEntry: BarEntry) =
                        if (barEntry.y != 0f) abs(barEntry.y).toInt().toString() else ""
                }
            }
        val accelSet = dataSet(levels.map { counts.accel[it].toFloat() }, "Aceleraciones")
        // Las de frenada, algo más apagadas: en el nivel Leve las dos barras se tocan en el cero y
        // del mismo color parecerían una sola
        val brakeSet = dataSet(levels.map { -counts.brake[it].toFloat() }, "Frenadas").apply {
            colors = levelColors.map { Color.argb(150, Color.red(it), Color.green(it), Color.blue(it)) }
        }
        // Margen por arriba y por abajo para que quepan los números de las barras más altas
        val peak = maxOf(1, counts.accel.max(), counts.brake.max()) * 1.25f

        chart.apply {
            data = BarData(accelSet, brakeSet).apply { barWidth = 0.7f }
            description.isEnabled = false
            legend.isEnabled = false
            axisRight.isEnabled = false
            axisLeft.apply {
                // Sin números en el eje: cada barra lleva el suyo. Solo la línea del cero
                setDrawLabels(false)
                setDrawGridLines(false)
                setDrawAxisLine(false)
                setDrawZeroLine(true)
                zeroLineColor = Color.parseColor("#666666")
                zeroLineWidth = 1f
                axisMinimum = -peak
                axisMaximum = peak
            }
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                textColor = Color.parseColor("#B0B0B0")
                granularity = 1f
                labelCount = AccelEventCounter.LEVELS
                axisMinimum = -0.5f
                axisMaximum = AccelEventCounter.LEVELS - 0.5f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = levelNames.getOrElse(value.toInt()) { "" }
                }
            }
            setTouchEnabled(false)
            invalidate()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupChart(
        chart: LineChart, values: List<Float>, label: String, unit: String, format: String,
        colorHex: String, time: (Int) -> String,
        // Índice de muestra de values[0]: si no es 0, el gráfico empieza más tarde
        startIndex: Int = 0,
        // Dibuja los valores al revés (positivo hacia abajo): en la inclinación, la derecha va
        // abajo. El eje va sin signo y el marcador al tocar sigue mostrando el valor real
        invertY: Boolean = false
    ): LineChart {
        val color = Color.parseColor(colorHex)
        val dataSet = LineDataSet(
            values.mapIndexed { i, v -> Entry((i + startIndex).toFloat(), if (invertY) -v else v) }, label
        ).apply {
            this.color = color
            setDrawCircles(false)
            lineWidth = 2f
            setDrawFilled(true)
            fillColor = color
            fillAlpha = 68
            // Cruz de selección: líneas vertical y horizontal sobre el punto elegido
            highLightColor = Color.WHITE
            highlightLineWidth = 1f
            setDrawHorizontalHighlightIndicator(true)
            setDrawVerticalHighlightIndicator(true)
        }
        chart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            axisRight.isEnabled = false
            if (invertY) {
                axisLeft.valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = abs(value).toInt().toString()
                }
            }
            // El eje X son muestras: se muestra el tiempo de ruta en lugar del índice
            xAxis.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = time(value.toInt())
            }
            // Un toque o arrastre marca la muestra más cercana; sin zoom para no
            // confundirlo con el gesto de selección
            setTouchEnabled(true)
            isHighlightPerTapEnabled = true
            isHighlightPerDragEnabled = true
            setScaleEnabled(false)
            isDragEnabled = false
            setMaxHighlightDistance(1000f)
            marker = ChartMarkerView(requireContext()) { e ->
                "${String.format(format, if (invertY) -e.y else e.y)} $unit · ${time(e.x.toInt())}"
            }
            setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                override fun onValueSelected(e: Entry, h: Highlight) = selectSample(e.x.toInt(), chart)
                override fun onNothingSelected() = clearSelection()
            })
            // El ScrollView no debe robar el arrastre horizontal sobre la gráfica
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.parent.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            invalidate()
        }
        return chart
    }

    /** Marca la misma muestra en el resto de gráficas y pone un marcador en el mapa. */
    private fun selectSample(index: Int, source: LineChart) {
        if (syncingSelection) return
        syncingSelection = true
        charts.filter { it !== source }.forEach { it.highlightValue(index.toFloat(), 0) }
        syncingSelection = false

        val p = routePoints?.getOrNull(index)
        val map = googleMap
        if (p == null || map == null || (p.latitude == 0.0 && p.longitude == 0.0)) return
        val pos = LatLng(p.latitude, p.longitude)
        val marker = selectionMarker
        if (marker == null) {
            selectionMarker = map.addMarker(
                MarkerOptions().position(pos).title("Punto seleccionado")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )
        } else {
            marker.position = pos
        }
    }

    private fun clearSelection() {
        if (syncingSelection) return
        syncingSelection = true
        charts.forEach { it.highlightValues(null) }
        syncingSelection = false
        selectionMarker?.remove()
        selectionMarker = null
    }

    private fun setupExportButton() {
        binding.btnExportGpx.setOnClickListener {
            lifecycleScope.launch {
                val gpx = viewModel.exportRouteAsGpx(args.routeId)
                if (gpx != null) {
                    shareGpx(gpx)
                }
            }
        }
    }

    private fun setupCsvExportButton() {
        binding.btnExportCsv.setOnClickListener {
            val file = SensorLogger.fileFor(requireContext(), args.routeId)
            if (!file.exists()) {
                Toast.makeText(
                    requireContext(),
                    "Esta ruta no tiene registro de sensores: se grabó antes de activarlo",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            // Otra app solo puede leer el archivo a través de un content:// que
            // le da FileProvider (una ruta de archivo directa no le vale)
            val uri = FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Exportar registro CSV"))
        }
    }

    private fun shareGpx(gpxContent: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(android.content.Intent.EXTRA_TEXT, gpxContent)
        }
        startActivity(android.content.Intent.createChooser(intent, "Exportar GPX"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // El GoogleMap pertenece a la vista del SupportMapFragment, que muere
        // con la nuestra: no hay que retenerlo (fuga de memoria) ni reutilizarlo.
        googleMap = null
        routePoints = null
        selectionMarker = null
        charts = emptyList()
        _binding = null
    }

    companion object {
        // Centro por defecto cuando la ruta no tiene puntos (península ibérica)
        private val DEFAULT_LOCATION = LatLng(40.4168, -3.7038)
        private const val DEFAULT_ZOOM = 5f
        // Altura congelada al empezar: la altitud GPS cruda no cambia más que esto durante N puntos
        // seguidos, y la moto se ha movido al menos esa distancia
        private const val FROZEN_ALT_TOLERANCE_M = 0.05
        private const val FROZEN_ALT_MIN_POINTS = 5
        private const val FROZEN_ALT_MIN_DISTANCE_M = 150f
        // Precisión vertical peor que esto: el fix no se usa para la altura (como en el servicio)
        private const val POOR_VERTICAL_ACCURACY_M = 12f
        // Muestras a cada lado en la media del gráfico de velocidad (4 + 1 + 4 = ~9 s a 1 muestra/s)
        private const val SPEED_SMOOTHING_RADIUS = 4
        // Y en el de inclinación (2 + 1 + 2 = ~5 s)
        private const val LEAN_SMOOTHING_RADIUS = 2
        private const val SINGLE_POINT_ZOOM = 16f
        private const val MAP_PADDING_PX = 80
    }
}

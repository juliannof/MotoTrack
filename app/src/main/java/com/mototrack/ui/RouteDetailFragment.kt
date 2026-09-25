package com.mototrack.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
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
            binding.tvMaxLean.text   = String.format("I %.1f° · D %.1f°", route.maxLeanLeft, route.maxLeanRight)
            binding.tvMaxAccel.text  = String.format("%.2f m/s²", route.maxAcceleration)

            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            binding.tvDate.text = sdf.format(Date(route.startTime))

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

            // Gráficas
            if (points.isNotEmpty()) setupCharts(points)

            // Mapa (si onMapReady aún no llegó, se pintará desde allí)
            renderRouteOnMap()
        }
    }

    /**
     * Mapa de calor de la velocidad: el trazado cambia de verde (lento) a rojo
     * (rápido) respecto a la velocidad máxima de la ruta. Los tramos contiguos del
     * mismo tono se agrupan en una sola línea para no crear miles de polilíneas.
     */
    private fun drawHeatLine(map: GoogleMap, points: List<RoutePoint>) {
        val valid = points.filter { it.latitude != 0.0 || it.longitude != 0.0 }
        val maxSpeed = valid.maxOfOrNull { it.speedKmh }?.takeIf { it > 1f } ?: 1f
        binding.heatLegend.visibility = View.VISIBLE
        binding.tvHeatMax.text = String.format("%.0f km/h", maxSpeed)

        var run = mutableListOf<LatLng>()
        var runShade = -1
        fun flush() {
            if (run.size >= 2) {
                map.addPolyline(
                    PolylineOptions().addAll(run).width(10f).geodesic(true)
                        .color(heatColor(runShade / (HEAT_SHADES - 1f)))
                )
            }
        }
        for (i in 1 until valid.size) {
            // Cada tramo toma el tono de la velocidad media de sus dos extremos
            val v = (valid[i - 1].speedKmh + valid[i].speedKmh) / 2f
            val shade = ((v / maxSpeed).coerceIn(0f, 1f) * (HEAT_SHADES - 1)).toInt()
            if (shade != runShade) {
                flush()
                // El nuevo tramo arranca en el último punto: sin huecos entre colores
                run = mutableListOf(LatLng(valid[i - 1].latitude, valid[i - 1].longitude))
                runShade = shade
            }
            run.add(LatLng(valid[i].latitude, valid[i].longitude))
        }
        flush()
    }

    /** 0 = verde, 0.5 = amarillo, 1 = rojo. */
    private fun heatColor(t: Float) = Color.HSVToColor(floatArrayOf(120f * (1f - t), 0.9f, 1f))

    private fun setupCharts(points: List<RoutePoint>) {
        val t0 = points.first().timestamp
        val time = { i: Int ->
            val s = ((points[i.coerceIn(0, points.size - 1)].timestamp - t0) / 1000).toInt()
            String.format("%d:%02d", s / 60, s % 60)
        }
        // La altitud GPS es ruidosa: media móvil corta para que el perfil se lea
        val elevation = points.map { it.altitude.toFloat() }.let { alt ->
            alt.indices.map { i ->
                val from = maxOf(0, i - 3); val to = minOf(alt.lastIndex, i + 3)
                alt.subList(from, to + 1).average().toFloat()
            }
        }

        // Orden fijo: velocidad, aceleración, ángulo lateral, altura del terreno
        charts = listOf(
            setupChart(binding.chartSpeed, points.map { it.speedKmh }, "Velocidad", "km/h", "%.0f", "#00BCD4", time),
            setupAccelChart(points, time),
            setupChart(binding.chartLean, points.map { it.leanAngle }, "Ángulo lateral", "°", "%.1f", "#FF5722", time),
            setupChart(binding.chartElevation, elevation, "Altura", "m", "%.0f", "#B39DDB", time)
        )
    }

    /**
     * Aceleración en el sentido de la marcha. Las rutas grabadas antes de guardarla
     * (todo a 0) no tienen dato: se avisa en vez de dibujar una línea plana.
     */
    private fun setupAccelChart(points: List<RoutePoint>, time: (Int) -> String): LineChart {
        val chart = binding.chartAccel
        if (points.all { it.longAccel == 0f }) {
            chart.clear()
            chart.setNoDataText("Sin aceleración: ruta grabada antes de guardarla")
            chart.setNoDataTextColor(Color.parseColor("#888888"))
            chart.invalidate()
            return chart
        }
        return setupChart(chart, points.map { it.longAccel }, "Aceleración", "m/s²", "%+.1f", "#8BC34A", time)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupChart(
        chart: LineChart, values: List<Float>, label: String, unit: String, format: String,
        colorHex: String, time: (Int) -> String
    ): LineChart {
        val color = Color.parseColor(colorHex)
        val dataSet = LineDataSet(values.mapIndexed { i, v -> Entry(i.toFloat(), v) }, label).apply {
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
                "${String.format(format, e.y)} $unit · ${time(e.x.toInt())}"
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
        private const val SINGLE_POINT_ZOOM = 16f
        private const val MAP_PADDING_PX = 80
        private const val HEAT_SHADES = 16
    }
}

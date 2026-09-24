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
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
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
                map.addPolyline(
                    PolylineOptions()
                        .addAll(latLngs)
                        .color(ContextCompat.getColor(requireContext(), R.color.accent_cyan))
                        .width(10f)
                        .geodesic(true)
                )
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
            if (points.isNotEmpty()) {
                setupSpeedChart(points.map { it.speedKmh })
                setupLeanChart(points.map { it.leanAngle })
                setupAccelChart(points.map { it.accelTotal })
            }

            // Mapa (si onMapReady aún no llegó, se pintará desde allí)
            renderRouteOnMap()
        }
    }

    private fun setupSpeedChart(values: List<Float>) {
        val entries = values.mapIndexed { i, v -> Entry(i.toFloat(), v) }
        val dataSet = LineDataSet(entries, "Velocidad (km/h)").apply {
            color = Color.parseColor("#00BCD4")
            setDrawCircles(false)
            lineWidth = 2f
            setDrawFilled(true)
            fillColor = Color.parseColor("#4400BCD4")
        }
        binding.chartSpeed.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            axisRight.isEnabled = false
            invalidate()
        }
    }

    private fun setupLeanChart(values: List<Float>) {
        val entries = values.mapIndexed { i, v -> Entry(i.toFloat(), v) }
        val dataSet = LineDataSet(entries, "Lean angle (°)").apply {
            color = Color.parseColor("#FF5722")
            setDrawCircles(false)
            lineWidth = 2f
            setDrawFilled(true)
            fillColor = Color.parseColor("#44FF5722")
        }
        binding.chartLean.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            axisRight.isEnabled = false
            invalidate()
        }
    }

    private fun setupAccelChart(values: List<Float>) {
        val entries = values.mapIndexed { i, v -> Entry(i.toFloat(), v) }
        val dataSet = LineDataSet(entries, "Aceleración (m/s²)").apply {
            color = Color.parseColor("#8BC34A")
            setDrawCircles(false)
            lineWidth = 2f
            setDrawFilled(true)
            fillColor = Color.parseColor("#448BC34A")
        }
        binding.chartAccel.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            axisRight.isEnabled = false
            invalidate()
        }
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
        _binding = null
    }

    companion object {
        // Centro por defecto cuando la ruta no tiene puntos (península ibérica)
        private val DEFAULT_LOCATION = LatLng(40.4168, -3.7038)
        private const val DEFAULT_ZOOM = 5f
        private const val SINGLE_POINT_ZOOM = 16f
        private const val MAP_PADDING_PX = 80
    }
}

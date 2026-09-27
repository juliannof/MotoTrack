package com.mototrack.ui

import android.graphics.Bitmap
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.mototrack.R
import com.mototrack.data.RoutePoint
import com.mototrack.utils.Daylight
import com.mototrack.utils.HeatTrail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * Genera la captura del mapa de cada ruta con un mapa de Google oculto ([mapView], detrás de la
 * lista) y la guarda en [dir] como PNG: se hace una sola vez por ruta y la lista solo lee el
 * archivo. Las rutas se procesan de una en una. [onReady] avisa cuando una captura está lista.
 */
class MapThumbRenderer(
    private val mapView: MapView,
    private val dir: File,
    private val scope: CoroutineScope,
    private val pointsFor: suspend (Long) -> List<RoutePoint>,
    private val onReady: (Long) -> Unit
) {
    private val queue = ArrayDeque<Long>()
    private val requested = mutableSetOf<Long>()
    private var worker: Job? = null
    private var map: GoogleMap? = null

    fun file(routeId: Long) = File(dir, "route_$routeId.png")

    fun delete(routeId: Long) {
        file(routeId).delete()
        requested.remove(routeId)
    }

    /** Pide la captura de una ruta; no hace nada si ya existe o ya está pedida. */
    fun request(routeId: Long) {
        if (file(routeId).exists() || !requested.add(routeId)) return
        queue.addLast(routeId)
        if (worker?.isActive != true) worker = scope.launch { drain() }
    }

    private suspend fun drain() {
        val m = awaitMap()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (render(m, id)) onReady(id)
        }
    }

    private suspend fun awaitMap(): GoogleMap {
        map?.let { return it }
        val m = suspendCancellableCoroutine<GoogleMap> { c -> mapView.getMapAsync { c.resume(it) } }
        m.uiSettings.isMapToolbarEnabled = false
        // Primera carga completa del mapa (el callback solo se dispara una vez por mapa)
        withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { c -> m.setOnMapLoadedCallback { c.resume(Unit) } }
        }
        map = m
        return m
    }

    private suspend fun render(m: GoogleMap, routeId: Long): Boolean {
        val pts = pointsFor(routeId).filter { it.latitude != 0.0 || it.longitude != 0.0 }
        if (pts.size < 2) return false
        while (mapView.width == 0 || mapView.height == 0) delay(200)

        val latLngs = pts.map { LatLng(it.latitude, it.longitude) }
        // Estilo según la luz que había al empezar la ruta: mapa normal de día, oscuro de noche
        val night = Daylight.isNight(pts.first().latitude, pts.first().longitude, pts.first().timestamp)
        m.setMapStyle(if (night) MapStyleOptions.loadRawResourceStyle(mapView.context, R.raw.map_dark) else null)
        m.clear()
        HeatTrail.draw(m, latLngs, pts.map { it.speedKmh }, pts.maxOf { it.speedKmh }.coerceAtLeast(1f))
        val bounds = LatLngBounds.builder().apply { latLngs.forEach { include(it) } }.build()
        val pad = (24 * mapView.resources.displayMetrics.density).toInt()
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, mapView.width, mapView.height, pad))
        delay(TILES_WAIT_MS)   // los mapas nuevos de esa zona tardan en pintarse

        val bmp = suspendCancellableCoroutine<Bitmap?> { c -> m.snapshot { c.resume(it) } } ?: return false
        return withContext(Dispatchers.IO) {
            dir.mkdirs()
            val tmp = File(dir, "route_$routeId.tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(file(routeId))
        }
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 6_000L
        const val TILES_WAIT_MS = 2_000L
    }
}

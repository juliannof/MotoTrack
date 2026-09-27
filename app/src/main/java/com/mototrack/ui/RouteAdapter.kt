package com.mototrack.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.mototrack.data.Route
import com.mototrack.data.RoutePoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.mototrack.databinding.ItemRouteBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class RouteAdapter(
    private val scope: CoroutineScope,
    private val pointsFor: suspend (Long) -> List<RoutePoint>,
    private val thumbFile: (Long) -> File,
    private val requestThumb: (Long) -> Unit,
    private val onItemClick: (Route) -> Unit,
    private val onDeleteClick: (Route) -> Unit
) : ListAdapter<Route, RouteAdapter.RouteViewHolder>(DIFF_CALLBACK) {

    companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Route>() {
            override fun areItemsTheSame(a: Route, b: Route) = a.id == b.id
            override fun areContentsTheSame(a: Route, b: Route) = a == b
        }
    }

    inner class RouteViewHolder(private val binding: ItemRouteBinding) :
        RecyclerView.ViewHolder(binding.root) {

        // Miniatura del recorrido: solo existe en el layout horizontal
        private val thumb = binding.trackThumb
        private var loadJob: Job? = null

        private fun loadTrack(route: Route) {
            if (thumb == null) return
            val shot = thumbFile(route.id)
            if (shot.exists()) {
                // Captura del mapa ya generada
                thumb.visibility = View.GONE
                binding.ivMapThumb?.let {
                    it.visibility = View.VISIBLE
                    it.load(shot) {
                        transformations(RoundedCornersTransformation(8f * it.resources.displayMetrics.density))
                    }
                }
                return
            }
            // Mientras no hay captura: el dibujo del trazado, y se pide la captura (solo de rutas terminadas)
            binding.ivMapThumb?.visibility = View.GONE
            thumb.visibility = View.VISIBLE
            thumb.setTrack(emptyList())
            loadJob?.cancel()
            loadJob = scope.launch { thumb.setTrack(pointsFor(route.id)) }
            if (route.isCompleted) requestThumb(route.id)
        }

        fun clearTrack() {
            loadJob?.cancel()
            thumb?.setTrack(emptyList())
        }

        fun bind(route: Route) {
            loadTrack(route)
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

            binding.tvRouteName.text = route.name
            binding.tvDate.text = sdf.format(Date(route.startTime))
            binding.tvDistance.text = String.format("%.2f km", route.distanceKm)
            binding.tvMaxSpeed.text = String.format("Vmax: %.0f km/h", route.maxSpeedKmh)
            binding.tvMaxLean.text  = String.format("Lean I %.0f° · D %.0f°", route.maxLeanLeft, route.maxLeanRight)

            // Duración
            if (route.endTime > 0) {
                val millis = route.endTime - route.startTime
                val min = TimeUnit.MILLISECONDS.toMinutes(millis)
                val sec = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
                binding.tvDuration.text = String.format("%d:%02d min", min, sec)
            } else {
                binding.tvDuration.text = "—"
            }

            binding.root.setOnClickListener { onItemClick(route) }
            binding.btnDelete.setOnClickListener { onDeleteClick(route) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
        val binding = ItemRouteBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return RouteViewHolder(binding)
    }

    override fun onViewRecycled(holder: RouteViewHolder) {
        holder.clearTrack()
    }

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

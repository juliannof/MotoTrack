package com.mototrack.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mototrack.data.Route
import com.mototrack.databinding.ItemRouteBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class RouteAdapter(
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

        fun bind(route: Route) {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

            binding.tvRouteName.text = route.name
            binding.tvDate.text = sdf.format(Date(route.startTime))
            binding.tvDistance.text = String.format("%.2f km", route.distanceKm)
            binding.tvMaxSpeed.text = String.format("Vmax: %.0f km/h", route.maxSpeedKmh)
            binding.tvMaxLean.text  = String.format("Max lean: %.1f°", route.maxLeanAngle)

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

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

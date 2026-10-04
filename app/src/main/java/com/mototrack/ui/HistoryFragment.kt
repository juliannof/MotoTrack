package com.mototrack.ui

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.mototrack.data.Route
import com.mototrack.databinding.FragmentHistoryBinding

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: RouteAdapter
    private var thumbs: MapThumbRenderer? = null

    private companion object {
        const val THUMBS_DIR = "route_maps"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        // Las capturas de antes eran todas del mapa oscuro: fuera
        File(requireContext().filesDir, "route_thumbs").deleteRecursively()

        // Capturas del mapa (solo en horizontal, donde la lista tiene miniaturas)
        binding.mapRender?.let { mv ->
            mv.onCreate(null)
            thumbs = MapThumbRenderer(
                mapView = mv,
                dir = File(requireContext().filesDir, THUMBS_DIR),
                scope = viewLifecycleOwner.lifecycleScope,
                pointsFor = { id -> viewModel.routePoints(id) },
                onReady = { id ->
                    val i = adapter.currentList.indexOfFirst { it.id == id }
                    if (i >= 0) adapter.notifyItemChanged(i)
                }
            )
        }

        adapter = RouteAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            pointsFor = { id -> viewModel.routePoints(id) },
            styleFor = { route -> viewModel.drivingStyle(route) },
            thumbFile = { id -> thumbs?.file(id) ?: File(requireContext().filesDir, "$THUMBS_DIR/route_$id.png") },
            requestThumb = { id -> thumbs?.request(id) },
            onItemClick = { route ->
                val action = HistoryFragmentDirections.actionHistoryToDetail(route.id)
                findNavController().navigate(action)
            },
            onDeleteClick = { route -> confirmDelete(route) }
        )

        binding.recyclerRoutes.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HistoryFragment.adapter
        }

        viewModel.allRoutes.observe(viewLifecycleOwner) { routes ->
            adapter.submitList(routes)
            binding.tvEmpty.visibility = if (routes.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun confirmDelete(route: Route) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Borrar ruta")
            .setMessage("¿Borrar \"${route.name}\" y todos sus puntos? No se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                thumbs?.delete(route.id)
                viewModel.deleteRoute(route)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // El MapView oculto necesita el ciclo de vida del fragmento
    override fun onStart() { super.onStart(); _binding?.mapRender?.onStart() }
    override fun onResume() { super.onResume(); _binding?.mapRender?.onResume() }
    override fun onPause() { _binding?.mapRender?.onPause(); super.onPause() }
    override fun onStop() { _binding?.mapRender?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); _binding?.mapRender?.onLowMemory() }

    override fun onDestroyView() {
        _binding?.mapRender?.onDestroy()
        thumbs = null
        super.onDestroyView()
        _binding = null
    }
}

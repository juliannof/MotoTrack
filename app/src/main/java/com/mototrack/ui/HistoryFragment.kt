package com.mototrack.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.mototrack.data.Route
import com.mototrack.databinding.FragmentHistoryBinding

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: RouteAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        adapter = RouteAdapter(
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
        AlertDialog.Builder(requireContext())
            .setTitle("Borrar ruta")
            .setMessage("¿Borrar \"${route.name}\" y todos sus puntos? No se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ -> viewModel.deleteRoute(route) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

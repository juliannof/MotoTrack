package com.mototrack.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.mototrack.R
import com.mototrack.auth.AuthRepository
import com.mototrack.databinding.FragmentLoginBinding

/** Pantalla de acceso: iniciar sesión o crear cuenta. */
class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    private var registering = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val auth = AuthRepository(requireContext())
        showMode()

        binding.tvToggle.setOnClickListener {
            registering = !registering
            showMode()
        }
        binding.btnSubmit.setOnClickListener {
            val email = binding.etEmail.text.toString()
            val password = binding.etPassword.text.toString()
            val error = if (registering) auth.register(email, password) else auth.login(email, password)
            if (error != null) {
                binding.tvError.text = error
                binding.tvError.visibility = View.VISIBLE
            } else {
                // Vaciar la pila: con la sesión iniciada, "atrás" no vuelve al login
                findNavController().navigate(
                    R.id.nav_dashboard, null,
                    NavOptions.Builder().setPopUpTo(R.id.nav_graph, true).build()
                )
            }
        }
    }

    private fun showMode() {
        binding.tvError.visibility = View.GONE
        binding.btnSubmit.text = if (registering) "CREAR CUENTA" else "ENTRAR"
        binding.tvToggle.text = if (registering) "¿Ya tienes cuenta? Entra" else "¿No tienes cuenta? Regístrate"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

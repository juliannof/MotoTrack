package com.mototrack.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.mototrack.BuildConfig
import com.mototrack.R
import com.mototrack.auth.AuthRepository
import com.mototrack.databinding.FragmentLoginBinding
import kotlinx.coroutines.launch

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
        // Sin ID de cliente configurado (local.properties) el botón no se muestra
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID != "DEFAULT_CLIENT_ID") {
            binding.btnGoogle.visibility = View.VISIBLE
            binding.btnGoogle.setOnClickListener { signInWithGoogle(auth) }
        }
        binding.btnSubmit.setOnClickListener {
            val email = binding.etEmail.text.toString()
            val password = binding.etPassword.text.toString()
            val error = if (registering) auth.register(email, password) else auth.login(email, password)
            if (error != null) showError(error) else enterApp()
        }
    }

    private fun signInWithGoogle(auth: AuthRepository) {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetGoogleIdOption.Builder()
                    .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                    // false: ofrece todas las cuentas del móvil, no solo las ya usadas en la app
                    .setFilterByAuthorizedAccounts(false)
                    .build()
            )
            .build()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = CredentialManager.create(requireContext())
                    .getCredential(requireActivity(), request)
                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    auth.startGoogleSession(GoogleIdTokenCredential.createFrom(credential.data).id)
                    enterApp()
                } else {
                    showError("No se pudo iniciar sesión con Google")
                }
            } catch (e: GetCredentialCancellationException) {
                // El usuario cerró el selector de cuentas: no es un error
            } catch (e: GetCredentialException) {
                showError("No se pudo iniciar sesión con Google")
            }
        }
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }

    /** Vaciar la pila: con la sesión iniciada, "atrás" no vuelve al login. */
    private fun enterApp() {
        (requireActivity() as MainActivity).viewModel.onSessionChanged()
        findNavController().navigate(
            R.id.nav_dashboard, null,
            NavOptions.Builder().setPopUpTo(R.id.nav_graph, true).build()
        )
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

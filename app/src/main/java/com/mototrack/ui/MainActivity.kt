package com.mototrack.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.snackbar.Snackbar
import com.mototrack.R
import com.mototrack.databinding.ActivityMainBinding
import androidx.navigation.fragment.NavHostFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    lateinit var viewModel: MainViewModel

    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.all { it.value }) {
                // Permisos concedidos
            } else {
                Snackbar.make(
                    binding.root,
                    "Se requieren permisos de ubicación para grabar rutas",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Mantener pantalla activa mientras graba
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setupNavigation()
        checkPermissions()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        val appBarConfig = AppBarConfiguration(
            setOf(R.id.nav_dashboard, R.id.nav_history)
        )
        setupActionBarWithNavController(navController, appBarConfig)
        binding.bottomNav.setupWithNavController(navController)

        // En horizontal el Dashboard es el HUD de la moto: sin barra de título
        // para dar toda la altura a la velocidad. Al girar el móvil Android
        // recrea la Activity (onCreate de nuevo), así que basta con mirar la
        // orientación aquí.
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (landscape && destination.id == R.id.nav_dashboard) supportActionBar?.hide()
            else supportActionBar?.show()
            updateBottomNav(landscape, destination.id)
        }

        // Grabando en horizontal tampoco hace falta la barra inferior: más alto
        // para la velocidad. Al pulsar DETENER vuelve a aparecer.
        viewModel.isRecording.observe(this) {
            updateBottomNav(landscape, navController.currentDestination?.id)
        }
    }

    private fun updateBottomNav(landscape: Boolean, destinationId: Int?) {
        val hide = landscape && destinationId == R.id.nav_dashboard &&
            viewModel.isRecording.value == true
        binding.bottomNav.visibility = if (hide) View.GONE else View.VISIBLE
    }

    private fun checkPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}

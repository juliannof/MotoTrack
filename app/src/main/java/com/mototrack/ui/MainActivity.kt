package com.mototrack.ui

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.snackbar.Snackbar
import com.mototrack.R
import com.mototrack.auth.AuthRepository
import com.mototrack.databinding.ActivityMainBinding
import androidx.navigation.fragment.NavHostFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    lateinit var viewModel: MainViewModel
    private var currentDestinationId: Int? = null
    private val auth by lazy { AuthRepository(this) }

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
            if (destination.id == R.id.nav_login ||
                (landscape && destination.id == R.id.nav_dashboard)) supportActionBar?.hide()
            else supportActionBar?.show()
            updateBottomNav(landscape, destination.id)
            currentDestinationId = destination.id
            applyDashboardMode()
            invalidateOptionsMenu()
        }

        // Sin sesión: pantalla de acceso (la pila queda solo con ella, "atrás" sale)
        if (auth.currentUser() == null && navController.currentDestination?.id != R.id.nav_login) {
            goToLogin(navController)
        }

        // Grabando en horizontal tampoco hace falta la barra inferior: más alto
        // para la velocidad. Al pulsar DETENER vuelve a aparecer.
        viewModel.isRecording.observe(this) {
            updateBottomNav(landscape, navController.currentDestination?.id)
        }
    }

    private fun goToLogin(navController: androidx.navigation.NavController) {
        navController.navigate(
            R.id.nav_login, null,
            NavOptions.Builder().setPopUpTo(R.id.nav_graph, true).build()
        )
    }

    private fun updateBottomNav(landscape: Boolean, destinationId: Int?) {
        val hide = destinationId == R.id.nav_login ||
            (landscape && destinationId == R.id.nav_dashboard &&
                viewModel.isRecording.value == true)
        binding.bottomNav.visibility = if (hide) View.GONE else View.VISIBLE
    }

    private val prefs get() = getSharedPreferences("mototrack_prefs", Context.MODE_PRIVATE)

    private fun cockpitHorizontal() = prefs.getString(PREF_COCKPIT_ORIENTATION, "vertical") == "horizontal"

    /**
     * Dashboard = cabina de mando: barras del sistema ocultas (se ven un momento
     * al deslizar desde el borde y vuelven a esconderse solas) y, si el ajuste es
     * Horizontal, orientación bloqueada en apaisado siguiendo el sensor para
     * acertar con el lado. En el resto de pantallas todo vuelve a la normalidad.
     */
    private fun applyDashboardMode() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (currentDestinationId == R.id.nav_dashboard) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = if (cockpitHorizontal())
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Al minimizar y volver, o tras una notificación/diálogo del sistema, Android
    // muestra las barras; al recuperar el foco se vuelven a ocultar.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyDashboardMode()
    }

    /** Alterna Horizontal/Vertical y lo aplica al momento. Devuelve true si queda Horizontal. */
    fun toggleCockpitOrientation(): Boolean {
        val horizontal = !cockpitHorizontal()
        prefs.edit().putString(PREF_COCKPIT_ORIENTATION, if (horizontal) "horizontal" else "vertical").apply()
        applyDashboardMode()
        invalidateOptionsMenu()
        return horizontal
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_dashboard_orientation)?.apply {
            isVisible = currentDestinationId != R.id.nav_dashboard &&
                currentDestinationId != R.id.nav_login
            title = if (cockpitHorizontal()) "Cabina de Mando: Horizontal (cambiar a Vertical)"
            else "Cabina de Mando: Vertical (cambiar a Horizontal)"
        }
        menu.findItem(R.id.action_logout)?.isVisible = currentDestinationId != R.id.nav_login
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_dashboard_orientation) {
            toggleCockpitOrientation()
            return true
        }
        if (item.itemId == R.id.action_logout) {
            auth.logout()
            goToLogin(findNavController(R.id.nav_host_fragment))
            return true
        }
        return super.onOptionsItemSelected(item)
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

    companion object {
        private const val PREF_COCKPIT_ORIENTATION = "cockpit_orientation"
    }
}

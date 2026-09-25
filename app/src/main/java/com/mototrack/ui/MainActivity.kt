package com.mototrack.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
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
import androidx.navigation.ui.NavigationUI
import androidx.drawerlayout.widget.DrawerLayout
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.snackbar.Snackbar
import com.mototrack.R
import com.mototrack.auth.AuthRepository
import com.mototrack.databinding.ActivityMainBinding
import androidx.navigation.fragment.NavHostFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    lateinit var viewModel: MainViewModel
    private var currentDestinationId: Int? = null
    private lateinit var appBarConfig: AppBarConfiguration
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

        // Rutas de antes de las cuentas: pasan a la sesión que ya esté abierta
        viewModel.onSessionChanged()

        setupNavigation()
        checkPermissions()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        // Con el DrawerLayout en la config, la barra superior muestra la hamburguesa
        appBarConfig = AppBarConfiguration(
            setOf(R.id.nav_dashboard, R.id.nav_history), binding.drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfig)

        binding.navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.action_logout -> {
                    auth.logout()
                    viewModel.onSessionChanged()
                    goToLogin(navController)
                }
                else -> NavigationUI.onNavDestinationSelected(item, navController)
            }
            binding.drawerLayout.closeDrawers()
            true
        }

        // En horizontal el Dashboard es el HUD de la moto: sin barra de título
        // para dar toda la altura a la velocidad. Al girar el móvil Android
        // recrea la Activity (onCreate de nuevo), así que basta con mirar la
        // orientación aquí.
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.nav_login ||
                (landscape && destination.id == R.id.nav_dashboard)) supportActionBar?.hide()
            else supportActionBar?.show()
            // En el acceso el menú lateral no debe poder abrirse
            binding.drawerLayout.setDrawerLockMode(
                if (destination.id == R.id.nav_login) DrawerLayout.LOCK_MODE_LOCKED_CLOSED
                else DrawerLayout.LOCK_MODE_UNLOCKED
            )
            binding.navView.setCheckedItem(destination.id)
            currentDestinationId = destination.id
            updateDrawerHeader()
            applyDashboardMode()
        }

        // Sin sesión: pantalla de acceso (la pila queda solo con ella, "atrás" sale)
        if (auth.currentUser() == null && navController.currentDestination?.id != R.id.nav_login) {
            goToLogin(navController)
        }
    }

    private fun goToLogin(navController: androidx.navigation.NavController) {
        navController.navigate(
            R.id.nav_login, null,
            NavOptions.Builder().setPopUpTo(R.id.nav_graph, true).build()
        )
    }

    /** Foto (la de Google, si la hay), nombre y correo de la cuenta en la cabecera del menú. */
    private fun updateDrawerHeader() {
        val header = binding.navView.getHeaderView(0)
        val avatar = header.findViewById<ImageView>(R.id.iv_avatar)
        header.findViewById<TextView>(R.id.tv_user_name).text = auth.displayName() ?: ""
        header.findViewById<TextView>(R.id.tv_user_email).text = auth.currentUser() ?: ""
        val photo = auth.photoUrl()
        if (photo != null) {
            avatar.load(photo) {
                transformations(CircleCropTransformation())
                placeholder(R.drawable.ic_person)
                error(R.drawable.ic_person)
            }
        } else {
            avatar.setImageResource(R.drawable.ic_person)
        }
    }

    /**
     * Dashboard = cabina de mando: barras del sistema ocultas (se ven un momento
     * al deslizar desde el borde y vuelven a esconderse solas). En el resto de
     * pantallas las barras vuelven a la normalidad.
     */
    private fun applyDashboardMode() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (currentDestinationId == R.id.nav_dashboard) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Al minimizar y volver, o tras una notificación/diálogo del sistema, Android
    // muestra las barras; al recuperar el foco se vuelven a ocultar.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyDashboardMode()
    }

    private fun checkPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // La hamburguesa es el botón "arriba" de un destino principal: con el DrawerLayout en la
    // configuración, navigateUp(appBarConfig) abre el menú lateral (antes no hacía nada)
    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment)
        return NavigationUI.navigateUp(navController, appBarConfig) || super.onSupportNavigateUp()
    }

}

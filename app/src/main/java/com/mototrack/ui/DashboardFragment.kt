package com.mototrack.ui

import android.app.AlertDialog
import android.content.res.Configuration
import android.os.Bundle
import android.view.*
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.mototrack.R
import com.mototrack.databinding.FragmentDashboardBinding
import com.mototrack.service.CalibrationStatus
import com.mototrack.utils.AltitudeMonitor
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: MainViewModel
    private var altitudeMonitor: AltitudeMonitor? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        setupObservers()
        setupButtons()
    }

    /** Señal con el límite; si lo superas, la señal, la velocidad y "km/h" se ponen en rojo. */
    private fun updateSpeedLimitSign() {
        val limit = viewModel.currentSpeedLimit.value ?: 0
        val exceeded = viewModel.overSpeedLimit.value ?: false
        binding.speedLimitSign.setEstimated(viewModel.speedLimitEstimated.value ?: false)
        binding.speedLimitSign.setLimit(limit)
        binding.speedLimitSign.setExceeded(exceeded)

        val color = ContextCompat.getColor(
            requireContext(), if (exceeded) R.color.over_limit_red else R.color.text_primary)
        binding.tvSpeed.setTextColor(color)
        binding.tvSpeedUnit.setTextColor(
            if (exceeded) color else ContextCompat.getColor(requireContext(), R.color.text_secondary))
    }

    private fun setupObservers() {

        // Velocidad
        viewModel.currentSpeed.observe(viewLifecycleOwner) { speed ->
            updateAltitudeMeter()
            updateDistanceMeter()
            binding.tvSpeed.text = String.format("%.0f", speed)
            updateSpeedLimitSign()
        }

        // Límite de la vía (OpenStreetMap)
        viewModel.currentSpeedLimit.observe(viewLifecycleOwner) { updateSpeedLimitSign() }
        viewModel.speedLimitEstimated.observe(viewLifecycleOwner) { updateSpeedLimitSign() }
        viewModel.overSpeedLimit.observe(viewLifecycleOwner) { updateSpeedLimitSign() }

        // Vúmetro: necesita el signo para saber hacia qué lado encender
        viewModel.currentLeanSigned.observe(viewLifecycleOwner) { lean ->
            binding.leanMeter.setLean(lean)
        }

        // Aceleración
        // Vúmetro vertical: positivo = acelerando, negativo = frenando
        viewModel.longitudinalAccel.observe(viewLifecycleOwner) { accel ->
            binding.tvAccel.text = String.format("%+.1f", accel)
            binding.accelMeter.setAccel(accel)
        }

        // Altura sobre el nivel del mar
        viewModel.currentAltitude.observe(viewLifecycleOwner) { alt ->
            binding.tvAltitude.text = String.format("%.0f m", alt)
            // Azul por debajo del nivel del mar; naranja en el resto
            val color = ContextCompat.getColor(
                requireContext(), if (alt < 0) R.color.below_sea_blue else R.color.accent_orange)
            binding.tvAltitude.setTextColor(color)
            binding.altitudeMeter.setColor(color)
            updateAltitudeMeter()
        }
        viewModel.maxAltitude.observe(viewLifecycleOwner) { updateAltitudeMeter() }
        // La ruta más larga que has hecho es la referencia del vúmetro de distancia
        viewModel.allRoutes.observe(viewLifecycleOwner) { updateDistanceMeter() }

        // Calibración del ángulo: se avisa en la propia tarjeta de inclinación
        viewModel.calibrationStatus.observe(viewLifecycleOwner) { showCalibration(it) }

        // Distancia
        viewModel.distanceKm.observe(viewLifecycleOwner) { km ->
            binding.tvDistance.text = String.format("%.2f km", km)
            updateDistanceMeter()
        }

        // Conteo de puntos
        viewModel.pointCount.observe(viewLifecycleOwner) { count ->
            binding.tvPointCount.text = "$count pts"
        }

        // Stats máximos
        viewModel.maxSpeed.observe(viewLifecycleOwner) { max ->
            binding.tvMaxSpeed.text = String.format("%.0f", max)
        }

        val showMaxLean = {
            binding.tvMaxLean.text = String.format(
                "I %.0f° · D %.0f°",
                viewModel.maxLeanLeft.value ?: 0f,
                viewModel.maxLeanRight.value ?: 0f
            )
        }
        viewModel.maxLeanLeft.observe(viewLifecycleOwner) { showMaxLean() }
        viewModel.maxLeanRight.observe(viewLifecycleOwner) { showMaxLean() }

        viewModel.avgSpeed.observe(viewLifecycleOwner) { avg ->
            binding.tvAvgSpeed.text = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) String.format("%.0f km/h", avg)
                                      else String.format("Vmed: %.0f km/h", avg)
        }

        // Estado de grabación → actualizar UI
        viewModel.isRecording.observe(viewLifecycleOwner) { recording ->
            updateAltitudeMeter()
            updateDistanceMeter()
            if (recording) {
                binding.btnRecord.text = "⏹ DETENER"
                binding.btnRecord.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.stop_red)
                )
                binding.recordingIndicator.visibility = View.VISIBLE
            } else {
                binding.btnRecord.text = "▶ INICIAR RUTA"
                binding.btnRecord.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.start_green)
                )
                binding.recordingIndicator.visibility = View.GONE
                resetUI()
            }
        }
    }

    /**
     * Último LED = la ruta terminada más larga. Sin ruta en curso, con la moto
     * parada o sin ninguna ruta de referencia, se encienden todos.
     */
    private fun updateDistanceMeter() {
        val km = viewModel.distanceKm.value ?: 0f
        val longest = viewModel.allRoutes.value.orEmpty()
            .filter { it.isCompleted }.maxOfOrNull { it.distanceKm } ?: 0f
        val recording = viewModel.isRecording.value == true
        val stopped = (viewModel.currentSpeed.value ?: 0f) < 1f
        val fraction = if (!recording || stopped || longest <= 0f) 1f else km / longest
        binding.distanceMeter.setFraction(fraction)
    }

    /**
     * Escala de la altura mínima a la máxima de la ruta (valen negativas): el último
     * LED es la máxima y el primero la mínima. Sin ruta en curso, con la moto parada
     * o sin desnivel que mostrar, se encienden todos.
     */
    private fun updateAltitudeMeter() {
        val alt = viewModel.currentAltitude.value ?: return
        val hi = viewModel.maxAltitude.value ?: Double.NaN
        val lo = viewModel.minAltitude.value ?: Double.NaN
        val recording = viewModel.isRecording.value == true
        val stopped = (viewModel.currentSpeed.value ?: 0f) < 1f
        val flat = hi.isNaN() || lo.isNaN() || hi - lo < MIN_ALTITUDE_RANGE_M
        val fraction = if (!recording || stopped || flat) 1f else ((alt - lo) / (hi - lo)).toFloat()
        binding.altitudeMeter.setFraction(fraction)
    }

    private fun showCalibration(status: CalibrationStatus) {
        val label = binding.tvLeanLabel
        val (text, colorRes) = when (status) {
            CalibrationStatus.WAITING -> "CALIBRAR: PARA Y MANILLAR IZQ." to R.color.accent_orange
            CalibrationStatus.MEASURING -> "CALIBRANDO… NO TE MUEVAS" to R.color.accent_orange
            CalibrationStatus.DONE -> "CALIBRADO ✓" to R.color.accent_green
            CalibrationStatus.EXPIRED -> "SIN CALIBRAR (OFFSET ANTERIOR)" to R.color.text_secondary
            CalibrationStatus.OFF -> "INCLINACIÓN" to R.color.text_secondary
        }
        label.text = text
        label.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
        // Mientras se calibra el aviso ocupa toda la fila; el máximo vuelve después
        val calibrating = status == CalibrationStatus.WAITING || status == CalibrationStatus.MEASURING
        binding.tvMaxLean.visibility = if (calibrating) View.GONE else View.VISIBLE
        binding.tvMaxLeanTitle.visibility = if (calibrating) View.GONE else View.VISIBLE

        // "Calibrado" y "sin calibrar" se muestran unos segundos y vuelven a la etiqueta normal
        if (status == CalibrationStatus.DONE || status == CalibrationStatus.EXPIRED) {
            label.postDelayed({
                if (_binding != null && viewModel.calibrationStatus.value == status) {
                    binding.tvLeanLabel.text = "INCLINACIÓN"
                    binding.tvLeanLabel.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.text_secondary))
                }
            }, 4000)
        }
    }

    private fun setupButtons() {
        binding.btnRecord.setOnClickListener {
            if (viewModel.isRecording.value == true) {
                viewModel.stopTracking()
            } else {
                showStartDialog()
            }
        }
    }

    private fun showStartDialog() {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val defaultName = "Ruta ${sdf.format(Date())}"

        val input = EditText(requireContext()).apply {
            setText(defaultName)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Nueva Ruta")
            .setMessage("Nombre de la ruta:")
            .setView(input)
            .setPositiveButton("Iniciar") { _, _ ->
                val name = input.text.toString().ifBlank { defaultName }
                viewModel.startTracking(name)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun resetUI() {
        binding.tvSpeed.text = "0"
        binding.tvAccel.text = "+0.0"
        binding.accelMeter.reset()
        binding.tvDistance.text = "0.00 km"
        binding.tvAltitude.text = "— m"
        binding.leanMeter.reset()
    }

    // La altura se mantiene al día con el Dashboard a la vista y sin grabar;
    // grabando la da el servicio, y fuera del Dashboard no se gasta GPS.
    override fun onStart() {
        super.onStart()
        val monitor = altitudeMonitor ?: AltitudeMonitor(requireContext()).also { altitudeMonitor = it }
        viewModel.isRecording.observe(viewLifecycleOwner) { recording ->
            if (recording) monitor.stop() else monitor.start()
        }
    }

    override fun onStop() {
        super.onStop()
        altitudeMonitor?.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        // Por debajo de este desnivel la escala no dice nada: se enciende todo
        const val MIN_ALTITUDE_RANGE_M = 2.0
    }
}

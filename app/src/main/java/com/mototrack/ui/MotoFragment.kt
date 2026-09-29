package com.mototrack.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mototrack.R
import com.mototrack.databinding.FragmentMotoBinding
import com.mototrack.utils.LeanMath
import com.mototrack.utils.MotoProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * "Mi moto": los datos de la moto y la calibración del cero de inclinación en parado.
 *
 * La calibración mide durante [MEASURE_MS] el ángulo del móvil respecto a la vertical, con la
 * moto derecha, y lo guarda como cero (el mismo que usa el servicio de grabación). Es fiable si
 * el móvil está quieto y la moto de verdad derecha; esto último no lo puede saber el sensor, por
 * eso se rechazan las medidas inestables o muy grandes y se pide confirmación si el nuevo cero
 * se aleja mucho del anterior (caballete lateral, pie en el suelo…).
 */
class MotoFragment : Fragment(), SensorEventListener {

    private var _binding: FragmentMotoBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: MainViewModel

    private lateinit var sensorManager: SensorManager
    private val rotMatrix = FloatArray(9)

    /** Cero de inclinación vigente: la lectura en directo se muestra ya con él restado. */
    private var savedOffset = 0f
    private var lastUiNs = 0L

    // Medición en curso (startNs = 0: no hay ninguna)
    private var measureStartNs = 0L
    private val samples = ArrayList<Float>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMotoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val moto = MotoProfile.load(requireContext())
        binding.etMotoName.setText(moto.name)
        binding.etMotoBrand.setText(moto.brand)
        binding.etMotoModel.setText(moto.model)
        binding.etMotoYear.setText(moto.year)
        binding.spMotoMount.adapter = ArrayAdapter(
            requireContext(), R.layout.item_spinner, listOf("Elige una ubicación…") + MotoProfile.MOUNTS
        ).apply { setDropDownViewResource(R.layout.item_spinner) }
        binding.spMotoMount.setSelection(MotoProfile.MOUNTS.indexOf(moto.mount) + 1)   // 0 = sin elegir
        binding.spMotoMount.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) =
                updateCalibrateButton()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.btnSaveMoto.setOnClickListener {
            if (saveMoto()) Toast.makeText(requireContext(), "Moto guardada", Toast.LENGTH_SHORT).show()
        }

        binding.tvMotoStatus.text = PROMPT
        binding.motoLeanMeter.showScale = false
        binding.btnCalibrate.setOnClickListener { startMeasuring() }
        // No se calibra con una ruta en marcha: el servicio está usando y ajustando ese cero
        viewModel.isRecording.observe(viewLifecycleOwner) { updateCalibrateButton() }
        showLastCalibration()
    }

    override fun onResume() {
        super.onResume()
        savedOffset = MotoProfile.leanOffset(requireContext())
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: run {
            binding.tvMotoStatus.text = "Este móvil no tiene el sensor de rotación necesario."
            binding.btnCalibrate.isEnabled = false
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        cancelMeasuring()
        saveMoto()
    }

    // ── Datos de la moto ──────────────────────────────────────────────────────

    /** false (y avisa) si el año no es de 4 cifras; vacío vale. */
    private fun saveMoto(): Boolean {
        val b = _binding ?: return false
        val year = b.etMotoYear.text.toString().trim()
        if (year.isNotEmpty() && year.length != 4) {
            b.etMotoYear.error = "4 cifras"
            return false
        }
        MotoProfile.save(
            requireContext(),
            MotoProfile.Moto(
                b.etMotoName.text.toString(), b.etMotoBrand.text.toString(),
                b.etMotoModel.text.toString(), year, currentMount()
            )
        )
        return true
    }

    /** Ubicación elegida del móvil, o vacío si no se ha elegido ninguna. */
    private fun currentMount(): String =
        _binding?.spMotoMount?.selectedItemPosition?.let { MotoProfile.MOUNTS.getOrNull(it - 1) } ?: ""

    // ── Calibración ───────────────────────────────────────────────────────────

    private fun updateCalibrateButton() {
        val b = _binding ?: return
        val recording = viewModel.isRecording.value == true
        val measuring = measureStartNs != 0L
        b.btnCalibrate.isEnabled = !recording && !measuring
        b.btnCalibrate.text = if (measuring) "CALIBRANDO…" else "CALIBRAR"
        if (recording) b.tvMotoStatus.text = "Detén la ruta para poder calibrar."
    }

    private fun startMeasuring() {
        samples.clear()
        measureStartNs = 1L   // se fija con la primera lectura
        updateCalibrateButton()
    }

    private fun cancelMeasuring() {
        measureStartNs = 0L
        samples.clear()
        _binding?.let { updateCalibrateButton() }
    }

    private fun failMeasuring(message: String) {
        cancelMeasuring()
        binding.tvMotoStatus.text = message
        binding.tvMotoStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.stop_red))
    }

    private fun finishMeasuring() {
        val n = samples.size
        val mean = samples.average()
        val std = sqrt(samples.sumOf { (it - mean) * (it - mean) } / n)
        cancelMeasuring()
        when {
            n < MIN_SAMPLES ->
                failMeasuring("Pocas lecturas del sensor. Inténtalo de nuevo.")
            std > MAX_STD_DEG ->
                failMeasuring("La moto se ha movido (variación de %.1f°). Déjala quieta y repite.".format(std))
            abs(mean) > MAX_OFFSET_DEG ->
                failMeasuring("Muy inclinada (%.1f°): ¿está derecha y el móvil bien puesto en el soporte?".format(mean))
            MotoProfile.hasLeanOffset(requireContext()) && abs(mean - savedOffset) > MAX_DIFF_FROM_SAVED_DEG ->
                confirmBigChange(mean.toFloat())
            else -> applyCalibration(mean.toFloat())
        }
    }

    /** El nuevo cero difiere mucho del anterior: puede ser la moto ladeada, se pregunta antes. */
    private fun confirmBigChange(measured: Float) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Cambio grande")
            .setMessage(
                ("El nuevo cero (%+.1f°) difiere %.1f° del actual (%+.1f°). Si la moto no estaba " +
                    "totalmente derecha (caballete lateral, pie en el suelo), saldrá mal. ¿Guardarlo?")
                    .format(measured, abs(measured - savedOffset), savedOffset)
            )
            .setPositiveButton("Guardar") { _, _ -> applyCalibration(measured) }
            .setNegativeButton("Cancelar") { _, _ ->
                _binding?.tvMotoStatus?.text = "Calibración cancelada: se mantiene el cero anterior."
            }
            .show()
    }

    private fun applyCalibration(measured: Float) {
        MotoProfile.applyCalibration(requireContext(), measured)
        savedOffset = measured
        binding.tvMotoStatus.text = "Calibrado ✓  Cero guardado: %+.1f°. Ahora la moto derecha marca 0°.".format(measured)
        binding.tvMotoStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_green))
        showLastCalibration()
    }

    private fun showLastCalibration() {
        val cal = MotoProfile.lastCalibration(requireContext())
        binding.tvMotoLastCalibration.text = if (cal == null) "Sin calibrar en parado todavía"
        else "Última calibración: %s · cero %+.1f°".format(
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(cal.atMs)), cal.offsetDeg)
    }

    // ── Sensor ────────────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        val b = _binding ?: return
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
        val lean = LeanMath.lateralLeanDegrees(rotMatrix, displayRotation())

        if (measureStartNs != 0L) {
            if (lean == null) {
                failMeasuring("El móvil no está bien puesto en el soporte (vertical, pantalla al piloto).")
                return
            }
            if (measureStartNs == 1L) {
                measureStartNs = event.timestamp
                b.tvMotoStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            }
            samples.add(lean)
            val leftMs = MEASURE_MS - (event.timestamp - measureStartNs) / 1_000_000L
            b.tvMotoStatus.text = "Calibrando… (%d s)".format(((leftMs + 999) / 1000).coerceAtLeast(0))
            if (leftMs <= 0) finishMeasuring()
        }

        // La lectura en directo se refresca unas 10 veces por segundo, no a la del sensor
        if (event.timestamp - lastUiNs < UI_INTERVAL_NS) return
        lastUiNs = event.timestamp
        if (lean == null) {
            b.tvMotoLean.text = "—"
            b.tvMotoPose.text = "Móvil fuera del soporte o demasiado tumbado"
            if (measureStartNs == 0L && viewModel.isRecording.value != true) {
                b.tvMotoStatus.text = PROMPT
                b.tvMotoStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            }
            return
        }
        b.tvMotoPose.text = poseDescription()
        val shown = lean - savedOffset
        b.tvMotoLean.text = "%+.1f°".format(shown)
        b.motoLeanMeter.setLean(shown)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Cómo está colocado el móvil, sacado de la gravedad: en vertical u horizontal y cuánto
     * se inclina la pantalla respecto a la vertical (hacia arriba si mira al cielo, como en un
     * soporte de manillar). Dónde va montado en la moto no lo puede saber el sensor.
     */
    private fun poseDescription(): String {
        // Eje "arriba" del mundo en coordenadas del móvil: la componente z sale de la pantalla
        val tilt = Math.toDegrees(Math.asin(rotMatrix[8].toDouble().coerceIn(-1.0, 1.0)))
        val landscape = displayRotation().let { it == Surface.ROTATION_90 || it == Surface.ROTATION_270 }
        val orientation = if (landscape) "horizontal" else "vertical"
        val screen = when {
            abs(tilt) < 3 -> "pantalla vertical"
            tilt > 0 -> "pantalla inclinada %.0f° hacia arriba".format(tilt)
            else -> "pantalla inclinada %.0f° hacia abajo".format(-tilt)
        }
        return "Móvil en $orientation, $screen"
    }

    private fun displayRotation(): Int =
        (requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val PROMPT = "Pon el teléfono en su soporte y la moto recta."
        const val MEASURE_MS = 3_000L
        const val MIN_SAMPLES = 30
        // Variación máxima (desviación típica) de la medida: quieta, con el motor al ralentí, queda
        // muy por debajo de esto
        const val MAX_STD_DEG = 0.5
        // Como en el servicio: una inclinación mayor que esto no es del soporte
        const val MAX_OFFSET_DEG = 15.0
        // Como en el servicio: más lejos que esto del cero vigente es una moto ladeada
        const val MAX_DIFF_FROM_SAVED_DEG = 4.0
        const val UI_INTERVAL_NS = 100_000_000L
    }
}

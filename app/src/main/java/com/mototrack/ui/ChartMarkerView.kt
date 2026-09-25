package com.mototrack.ui

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import com.mototrack.R

/** Globo que sigue al punto seleccionado en una gráfica: valor y momento de la ruta. */
class ChartMarkerView(
    context: Context,
    private val describe: (Entry) -> String
) : MarkerView(context, R.layout.marker_view) {

    private val label: TextView = findViewById(R.id.tv_marker)

    override fun refreshContent(e: Entry, highlight: Highlight) {
        label.text = describe(e)
        super.refreshContent(e, highlight)
    }

    // Centrado sobre el punto y por encima del dedo
    override fun getOffset(): MPPointF = MPPointF(-(width / 2f), -height.toFloat() - 12f)
}

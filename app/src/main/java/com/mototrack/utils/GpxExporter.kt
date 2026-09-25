package com.mototrack.utils

import com.mototrack.data.Route
import com.mototrack.data.RoutePoint
import java.text.SimpleDateFormat
import java.util.*

/**
 * Exporta una ruta al formato GPX estándar (compatible con Strava, Garmin, etc.)
 */
object GpxExporter {

    fun toGpxString(route: Route, points: List<RoutePoint>): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="MotoTrack"
  xmlns="http://www.topografix.com/GPX/1/1"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://www.topografix.com/GPX/1/1 
  http://www.topografix.com/GPX/1/1/gpx.xsd">""")

        sb.appendLine("  <metadata>")
        sb.appendLine("    <name>${escapeXml(route.name)}</name>")
        sb.appendLine("    <time>${sdf.format(Date(route.startTime))}</time>")
        sb.appendLine("  </metadata>")

        sb.appendLine("  <trk>")
        sb.appendLine("    <name>${escapeXml(route.name)}</name>")
        sb.appendLine("    <trkseg>")

        for (pt in points) {
            sb.appendLine("""      <trkpt lat="${pt.latitude}" lon="${pt.longitude}">""")
            sb.appendLine("        <ele>${String.format("%.1f", pt.altitude)}</ele>")
            sb.appendLine("        <time>${sdf.format(Date(pt.timestamp))}</time>")
            sb.appendLine("        <speed>${String.format("%.2f", pt.speedKmh / 3.6f)}</speed>")
            sb.appendLine("        <extensions>")
            sb.appendLine("          <leanAngle>${String.format("%.1f", pt.leanAngle)}</leanAngle>")
            sb.appendLine("          <accelTotal>${String.format("%.3f", pt.accelTotal)}</accelTotal>")
            sb.appendLine("          <longAccel>${String.format("%.3f", pt.longAccel)}</longAccel>")
            sb.appendLine("        </extensions>")
            sb.appendLine("      </trkpt>")
        }

        sb.appendLine("    </trkseg>")
        sb.appendLine("  </trk>")
        sb.appendLine("</gpx>")

        return sb.toString()
    }

    private fun escapeXml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

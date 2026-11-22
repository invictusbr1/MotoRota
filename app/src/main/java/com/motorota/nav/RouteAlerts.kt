package com.motorota.nav

import com.motorota.data.Hazard
import com.motorota.data.RadarStore
import com.motorota.routing.GeoPoint
import com.motorota.routing.RouteResult
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/** Um aviso que esta EM CIMA da rota, a `posM` metros do inicio. */
data class AlongAlert(
    val key: String,
    val posM: Double,
    val emoji: String,
    val title: String,
    val speech: String,
    val limitKmh: Int = 0,
    val isRadar: Boolean = false,
)

object RouteAlerts {

    fun compute(route: RouteResult, radars: RadarStore, hazards: List<Hazard>): List<AlongAlert> {
        val pts = route.points
        if (pts.size < 2) return emptyList()
        val out = ArrayList<AlongAlert>()

        // radares candidatos: os que estao nas celulas por onde a rota passa
        val cand = LinkedHashSet<com.motorota.data.Radar>()
        var i = 0
        while (i < pts.size) {
            cand.addAll(radars.near(pts[i])); i += 3
        }
        for (r in cand) {
            val hit = project(route, r.point, 30.0) ?: continue
            if (r.directionDeg != null) {
                val diff = angleDiff(hit.bearing, r.directionDeg.toDouble())
                if (diff > 70) continue // radar do outro sentido
            }
            val lim = if (r.limitKmh > 0) " ${r.limitKmh}" else ""
            out.add(
                AlongAlert(
                    key = "radar-${r.point.lat}-${r.point.lon}",
                    posM = hit.posM,
                    emoji = "📷",
                    title = if (r.limitKmh > 0) "Radar ${r.limitKmh} km/h" else "Radar",
                    speech = if (r.limitKmh > 0) "radar, limite de$lim quilômetros por hora" else "radar",
                    limitKmh = r.limitKmh,
                    isRadar = true,
                )
            )
        }

        for (h in hazards) {
            val hit = project(route, h.point, 40.0) ?: continue
            out.add(
                AlongAlert(
                    key = "h-${h.id}",
                    posM = hit.posM,
                    emoji = h.type.emoji,
                    title = h.type.label,
                    speech = h.type.speech,
                    isRadar = h.type == com.motorota.data.HazardType.RADAR_MOVEL,
                )
            )
        }
        return out.sortedBy { it.posM }
    }

    private class Hit(val posM: Double, val bearing: Double)

    /** posicao (m desde o inicio) do ponto sobre a rota, se estiver a menos de maxDist dela */
    private fun project(route: RouteResult, p: GeoPoint, maxDist: Double): Hit? {
        val pts = route.points
        var best = maxDist
        var hit: Hit? = null
        val k = cos(Math.toRadians(p.lat))
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            // descarte rapido
            if (abs(a.lat - p.lat) > 0.02 && abs(b.lat - p.lat) > 0.02) continue
            val ax = (a.lon - p.lon) * k * 111320.0
            val ay = (a.lat - p.lat) * 110540.0
            val bx = (b.lon - p.lon) * k * 111320.0
            val by = (b.lat - p.lat) * 110540.0
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val x = ax + t * dx
            val y = ay + t * dy
            val d = sqrt(x * x + y * y)
            if (d < best) {
                best = d
                val bearing = (Math.toDegrees(atan2(dx, dy)) + 360) % 360
                hit = Hit(route.cumDist[i] + (route.cumDist[i + 1] - route.cumDist[i]) * t, bearing)
            }
        }
        return hit
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}

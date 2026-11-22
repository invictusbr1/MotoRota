package com.motorota.nav

import com.motorota.routing.GeoPoint
import com.motorota.routing.RouteHint
import com.motorota.routing.RouteResult
import com.motorota.routing.formatDistance
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

data class NavState(
    val nextHint: RouteHint?,
    val distToHintM: Double,
    val remainingM: Double,
    val remainingS: Int,
    val offRouteM: Double,
    val progressIndex: Int,
    val arrived: Boolean,
    /** metros percorridos desde o inicio da rota */
    val alongM: Double = 0.0,
    /** posicao da moto projetada sobre a rota (onde a linha azul comeca) */
    val snapped: GeoPoint? = null,
)

/**
 * Acompanha a posicao do GPS ao longo da rota: qual a proxima manobra,
 * quanto falta e se saimos da rota.
 */
class Navigator(val route: RouteResult) {
    private var idx = 0
    private val announced = HashMap<Int, Int>() // hint index -> ultimo nivel anunciado

    fun update(p: GeoPoint): NavState {
        val pts = route.points
        if (pts.size < 2) return NavState(null, 0.0, 0.0, 0, 0.0, 0, arrived = true)
        // procura o trecho mais proximo numa janela a frente da posicao atual
        val from = max(0, idx - 10)
        val to = min(pts.size - 2, idx + 400)
        var bestD = Double.MAX_VALUE
        var bestI = idx
        var bestT = 0.0
        for (i in from..to) {
            val (d, t) = distToSegment(p, pts[i], pts[i + 1])
            if (d < bestD) {
                bestD = d; bestI = i; bestT = t
            }
        }
        // se ficou longe, procura na rota inteira (ex.: GPS pulou)
        if (bestD > 150 && pts.size > 2) {
            for (i in 0 until pts.size - 1) {
                val (d, t) = distToSegment(p, pts[i], pts[i + 1])
                if (d < bestD) {
                    bestD = d; bestI = i; bestT = t
                }
            }
        }
        if (bestD < 80) idx = bestI

        val along = route.cumDist[idx] + (route.cumDist[idx + 1] - route.cumDist[idx]) * bestT
        val total = route.cumDist.last()
        val remaining = (total - along).coerceAtLeast(0.0)

        val totalTime = route.cumTime.last()
        val remainingS = if (totalTime > 0) {
            val tHere = route.cumTime[idx] + (route.cumTime[idx + 1] - route.cumTime[idx]) * bestT
            (totalTime - tHere).toInt()
        } else (remaining / 22).toInt()

        val next = route.hints.firstOrNull { it.index > idx }
        val distHint = next?.let { route.cumDist[it.index.coerceAtMost(route.cumDist.size - 1)] - along } ?: remaining

        return NavState(
            nextHint = next,
            distToHintM = distHint.coerceAtLeast(0.0),
            remainingM = remaining,
            remainingS = remainingS,
            offRouteM = bestD,
            progressIndex = idx,
            // perto do ponto final so conta como chegada depois de rodar boa parte da rota
            // (em "ida e volta" a partida e o destino sao o mesmo lugar)
            arrived = remaining < 40 || (p.distanceTo(pts.last()) < 40 && along > total * 0.6),
            alongM = along,
            snapped = GeoPoint(
                pts[idx].lat + (pts[idx + 1].lat - pts[idx].lat) * bestT,
                pts[idx].lon + (pts[idx + 1].lon - pts[idx].lon) * bestT,
            ),
        )
    }

    /**
     * Devolve a frase a ser falada agora (ou null). Avisa em 3 momentos:
     * longe (conforme a velocidade), perto (~250 m) e na hora (~60 m).
     */
    fun speechFor(state: NavState, speedMs: Float): String? {
        val hint = state.nextHint ?: return null
        val d = state.distToHintM
        val farDist = if (speedMs > 22) 1500.0 else if (speedMs > 14) 800.0 else 400.0
        val level = when {
            d <= 70 -> 3
            d <= 280 -> 2
            d <= farDist && d > 300 -> 1
            else -> 0
        }
        if (level == 0) return null
        val last = announced[hint.index] ?: 0
        if (level <= last) return null
        announced[hint.index] = level
        val action = Instructions.text(hint)
        return if (level == 3) action.replaceFirstChar { it.uppercase() }
        else "Em ${formatDistance(d).replace("km", "quilômetros").replace(" m", " metros")}, $action"
    }

    private val alertLevels = HashMap<String, Int>()

    /** Aviso falado de radar / alerta que esta a frente na rota. */
    fun alertSpeech(alert: AlongAlert, distM: Double, speedMs: Float): String? {
        val far = if (speedMs > 22) 800.0 else 500.0
        val level = when {
            alert.isRadar && distM <= 180 -> 2
            distM <= far -> 1
            else -> 0
        }
        if (level == 0) return null
        val last = alertLevels[alert.key] ?: 0
        if (level <= last) return null
        alertLevels[alert.key] = level
        return if (level == 2) "Radar agora." + (if (alert.limitKmh > 0) " ${alert.limitKmh}." else "")
        else "Atenção: ${alert.speech} em ${formatDistance(distM).replace("km", "quilômetros").replace(" m", " metros")}."
    }

    fun limitAt(alongM: Double): Int? =
        route.speedLimits.firstOrNull { alongM >= it.startM && alongM < it.endM }?.kmh

    private fun distToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Pair<Double, Double> {
        // projecao local em metros (suficiente para trechos curtos)
        val k = cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * k * 111320.0
        val ay = (a.lat - p.lat) * 110540.0
        val bx = (b.lon - p.lon) * k * 111320.0
        val by = (b.lat - p.lat) * 110540.0
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 > 0) -(ax * dx + ay * dy) / len2 else 0.0
        t = t.coerceIn(0.0, 1.0)
        val x = ax + t * dx
        val y = ay + t * dy
        return Pair(kotlin.math.sqrt(x * x + y * y), t)
    }
}

object Instructions {
    fun text(h: RouteHint): String = when (h.cmd) {
        1 -> "siga em frente"
        2 -> "vire à esquerda"
        3 -> "mantenha-se levemente à esquerda"
        4 -> "vire bem à esquerda"
        5 -> "vire à direita"
        6 -> "mantenha-se levemente à direita"
        7 -> "vire bem à direita"
        8 -> "mantenha-se à esquerda"
        9 -> "mantenha-se à direita"
        10, 11, 15 -> "faça o retorno"
        13, 14 -> if (h.exit > 0) "na rotatória, pegue a ${h.exit}ª saída" else "entre na rotatória"
        17 -> "pegue a saída à esquerda"
        18 -> "pegue a saída à direita"
        else -> "siga a rota"
    }

    fun arrow(h: RouteHint?): String = when (h?.cmd) {
        null -> "🏁"
        1, 16 -> "⬆"
        2, 4 -> "⬅"
        3, 8, 17 -> "↖"
        5, 7 -> "➡"
        6, 9, 18 -> "↗"
        10, 11, 15 -> "↩"
        13, 14 -> "⟳"
        else -> "⬆"
    }
}

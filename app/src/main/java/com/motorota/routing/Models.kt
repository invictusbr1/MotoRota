package com.motorota.routing

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Os 3 perfis de rota. `code` é o valor passado para o perfil moto.brf. */
enum class Mode(val code: Int, val label: String, val emoji: String, val colorHex: String, val hint: String) {
    SEGURA(
        0, "Segura", "🛡️", "#10B981",
        "Prioriza rodovias externas e desvia com força de áreas urbanas densas e de risco."
    ),
    DESEMPENHO(
        1, "Desempenho", "🏍️", "#3B82F6",
        "Equilíbrio ideal: pista livre e contorno de cidades para render melhor."
    ),
    RAPIDA(
        2, "Rápida", "⚡", "#F59E0B",
        "Menor tempo estimado. Mantém desvios de áreas bloqueadas e avisa trechos críticos."
    ),
}

data class GeoPoint(val lat: Double, val lon: Double) {
    fun distanceTo(o: GeoPoint): Double = haversine(lat, lon, o.lat, o.lon)
}

data class Waypoint(val point: GeoPoint, val name: String)

/** Área de risco: um círculo que a rota nunca atravessa. */
data class RiskArea(
    val id: Long,
    val point: GeoPoint,
    val radiusM: Int,
    val label: String,
    val createdAt: Long,
)

data class RouteHint(
    /** Índice do ponto da rota onde a manobra acontece */
    val index: Int,
    /** Comando do BRouter (1=siga, 2=esquerda, 5=direita, 13=rotatória ...) */
    val cmd: Int,
    val exit: Int,
)

data class RouteResult(
    val mode: Mode,
    /** Aviso sobre como a rota foi calculada (ex.: sem as zonas urbanas porque demorou demais) */
    val note: String? = null,
    val points: List<GeoPoint>,
    /** Distância acumulada (m) até cada ponto */
    val cumDist: DoubleArray,
    /** Tempo acumulado (s) até cada ponto, estimado pelo motor */
    val cumTime: FloatArray,
    val distanceM: Int,
    val timeS: Int,
    /** % da distância em rodovia (motorway / trunk / primary) */
    val highwayPct: Int,
    /** Metros sem asfalto */
    val unpavedM: Int,
    /** Metros em paralelepípedo / bloquete */
    val badPavingM: Int,
    val hints: List<RouteHint>,
    /** Limite de velocidade por trecho (só onde o mapa informa) */
    val speedLimits: List<SpeedSegment> = emptyList(),
)

data class SpeedSegment(val startM: Double, val endM: Double, val kmh: Int)

fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

fun formatDistance(m: Double): String = when {
    m >= 10000 -> "${(m / 1000).toInt()} km"
    m >= 1000 -> String.format(java.util.Locale.US, "%.1f km", m / 1000).replace('.', ',')
    m >= 100 -> "${((m / 50).toInt() * 50)} m"
    else -> "${((m / 10).toInt() * 10).coerceAtLeast(0)} m"
}

fun formatDuration(s: Int): String {
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "${h}h${m.toString().padStart(2, '0')}" else "$m min"
}

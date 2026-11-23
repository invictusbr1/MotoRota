package com.motorota.data

import com.motorota.routing.GeoPoint
import com.motorota.routing.RouteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Resultado da previsao ao longo do caminho. */
data class RouteWeather(
    /** frase curta para o painel, ex.: "🌧️ Chuva provável a 120 km (~15:30)" */
    val rainText: String?,
    /** "🌇 Pôr do sol 18:12 · você chega 19:05 (50 min de noite)" ou null se chega com luz */
    val sunsetText: String?,
)

/**
 * Previsao do tempo gratis (Open-Meteo, sem chave) nos pontos da rota,
 * na hora em que a moto vai passar por cada um. E o calculo do por do sol (sem internet).
 */
object Weather {

    suspend fun along(route: RouteResult, startAt: LocalDateTime = LocalDateTime.now()): RouteWeather =
        withContext(Dispatchers.IO) {
            RouteWeather(rainText = rain(route, startAt), sunsetText = sunset(route, startAt))
        }

    /** sem internet: so o por do sol (calculado no proprio celular) */
    fun offline(route: RouteResult, startAt: LocalDateTime = LocalDateTime.now()) =
        RouteWeather(rainText = null, sunsetText = sunset(route, startAt))

    /** escolhe ate 8 pontos espalhados pela rota e pergunta a previsao de cada um numa consulta so */
    private fun rain(route: RouteResult, startAt: LocalDateTime): String? {
        val pts = route.points
        if (pts.size < 2) return null
        val total = route.cumDist.last()
        val n = when {
            total < 15_000 -> 1
            else -> (total / 40_000).toInt().coerceIn(2, 8)
        }
        val samples = (0 until n).map { i ->
            val target = if (n == 1) total / 2 else total * i / (n - 1)
            var idx = route.cumDist.indexOfFirst { it >= target }
            if (idx < 0) idx = pts.size - 1
            Triple(pts[idx], route.cumDist[idx], route.cumTime.getOrElse(idx) { 0f }.toLong().takeIf { it > 0 }
                ?: (route.cumDist[idx] / 22).toLong())
        }
        val lats = samples.joinToString(",") { "%.4f".format(java.util.Locale.US, it.first.lat) }
        val lons = samples.joinToString(",") { "%.4f".format(java.util.Locale.US, it.first.lon) }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
            "&hourly=precipitation_probability,precipitation&forecast_days=2&timezone=America%2FSao_Paulo"
        val body = get(url)
        // varios pontos -> lista; um ponto -> objeto
        val arr = if (body.trimStart().startsWith("[")) org.json.JSONArray(body) else org.json.JSONArray().put(JSONObject(body))
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        var worst: Triple<Int, Double, LocalDateTime>? = null // prob, km, hora
        for (i in 0 until minOf(arr.length(), samples.size)) {
            val h = arr.getJSONObject(i).getJSONObject("hourly")
            val times = h.getJSONArray("time")
            val prob = h.getJSONArray("precipitation_probability")
            val mm = h.getJSONArray("precipitation")
            val passAt = startAt.plusSeconds(samples[i].third)
            val key = passAt.withMinute(0).withSecond(0).withNano(0)
            for (t in 0 until times.length()) {
                if (LocalDateTime.parse(times.getString(t), fmt) != key) continue
                val p = prob.optInt(t, 0)
                val r = mm.optDouble(t, 0.0)
                // so conta como chuva se tiver probabilidade alta E alguma chuva prevista
                if (p >= 50 && r >= 0.3 && (worst == null || p > worst.first)) {
                    worst = Triple(p, samples[i].second / 1000, passAt)
                }
            }
        }
        val w = worst ?: return "☀️ Sem chuva prevista no caminho"
        val hora = w.third.format(DateTimeFormatter.ofPattern("HH:mm"))
        val onde = if (w.second < 3) "na saída" else "a ${w.second.toInt()} km"
        return "🌧️ Chuva provável ${w.first}% $onde (~$hora)"
    }

    private fun sunset(route: RouteResult, startAt: LocalDateTime): String? {
        val end = route.points.lastOrNull() ?: return null
        val arrive = startAt.plusSeconds(route.timeS.toLong())
        val sunsetToday = sunsetTime(end, arrive.toLocalDate()) ?: return null
        val sunriseToday = sunriseTime(end, arrive.toLocalDate()) ?: return null
        val f = DateTimeFormatter.ofPattern("HH:mm")
        return when {
            // sai com luz e chega depois do por do sol
            arrive.isAfter(sunsetToday) && startAt.isBefore(sunsetToday) -> {
                val min = java.time.Duration.between(sunsetToday, arrive).toMinutes()
                "🌇 Pôr do sol ${sunsetToday.format(f)} · chega ${arrive.format(f)} ($min min de noite)"
            }
            arrive.isAfter(sunsetToday) || arrive.isBefore(sunriseToday) -> "🌙 Viagem à noite"
            else -> null
        }
    }

    // ---- por do sol (formula NOAA simplificada; erro de ~1-2 min) ----
    fun sunsetTime(p: GeoPoint, d: LocalDate) = sunEvent(p, d, false)
    fun sunriseTime(p: GeoPoint, d: LocalDate) = sunEvent(p, d, true)

    private fun sunEvent(p: GeoPoint, d: LocalDate, rise: Boolean): LocalDateTime? {
        val n = d.dayOfYear
        val gamma = 2 * Math.PI / 365 * (n - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val lat = Math.toRadians(p.lat)
        val cosHa = cos(Math.toRadians(90.833)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)
        if (cosHa < -1 || cosHa > 1) return null
        val ha = Math.toDegrees(acos(cosHa))
        val utcMin = 720 - 4 * (p.lon + if (rise) ha else -ha) - eqTime
        val utc = d.atStartOfDay().plusSeconds((utcMin * 60).toLong())
        return utc.atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 7000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "MotoRota-Android")
        try {
            if (conn.responseCode != 200) throw java.io.IOException("Open-Meteo HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

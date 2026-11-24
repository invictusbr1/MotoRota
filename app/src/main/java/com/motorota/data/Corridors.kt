package com.motorota.data

import android.content.Context
import com.motorota.routing.GeoPoint
import com.motorota.routing.RouteResult
import org.json.JSONArray
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Trecho de rodovia/via expressa perigoso (assaltos): ex. Washington Luís, Rodoanel.
 * Diferente das áreas (comunidades), aqui quem "paga" é a PRÓPRIA rodovia ou a redondeza:
 * a rota procura um caminho alternativo razoável; se não tiver, passa e avisa.
 */
data class Corridor(
    val name: String,
    val city: String,
    val reason: String,
    /** Custo extra por metro rodado no trecho (modo Desempenho, de dia, dia de semana) */
    val weight: Double,
    val worseAtNight: Boolean,
    val lines: List<List<GeoPoint>>,
    /** Custo extra por metro nas RUAS LOCAIS em volta (~1,5 km): não trocar a rodovia pelos bairros vizinhos */
    val regionWeight: Double = 0.0,
) {
    /** Índice rápido (peso 1) para testar se um ponto da rota está em cima deste trecho */
    val probe: btools.router.CorridorIndex by lazy {
        btools.router.CorridorIndex(50.0).also { ix ->
            for (l in lines) ix.addLine(DoubleArray(l.size) { l[it].lat }, DoubleArray(l.size) { l[it].lon }, 1.0)
            ix.freeze()
        }
    }
    val minLat = lines.minOf { l -> l.minOf { it.lat } }
    val maxLat = lines.maxOf { l -> l.maxOf { it.lat } }
    val minLon = lines.minOf { l -> l.minOf { it.lon } }
    val maxLon = lines.maxOf { l -> l.maxOf { it.lon } }
}

/** Um trecho perigoso que a rota percorre: de onde até onde (metros desde o início). */
data class CorridorHit(val corridor: Corridor, val fromM: Double, val toM: Double) {
    val lengthM get() = toM - fromM
}

/** Zona do cálculo de rota: M = região metropolitana (só ruas pagam), D = área urbana densa (todas pagam). */
data class UrbanZone(val dense: Boolean, val point: GeoPoint, val radiusM: Double, val name: String)

object Corridors {
    @Volatile var all: List<Corridor> = emptyList(); private set
    @Volatile var zones: List<UrbanZone> = emptyList(); private set

    private const val METRO_WEIGHT = 1.0
    private const val DENSE_WEIGHT = 4.0
    private const val HALF_WIDTH_M = 35.0

    fun load(ctx: Context) {
        val arr = JSONArray(ctx.assets.open("trechos_risco.json").bufferedReader().use { it.readText() })
        val out = ArrayList<Corridor>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ls = o.getJSONArray("linhas")
            val lines = (0 until ls.length()).mapNotNull { j ->
                val l = ls.getJSONArray(j)
                val pts = (0 until l.length()).map { k ->
                    l.getJSONArray(k).let { GeoPoint(it.getDouble(0), it.getDouble(1)) }
                }
                pts.takeIf { it.size >= 2 }
            }
            if (lines.isEmpty()) continue
            out.add(
                Corridor(
                    name = o.getString("nome"),
                    city = o.getString("cidade"),
                    reason = o.getString("motivo"),
                    weight = o.getDouble("peso"),
                    worseAtNight = o.optBoolean("noite", true),
                    lines = lines,
                    regionWeight = o.optDouble("regiao", 0.0),
                )
            )
        }
        all = out
        zones = ctx.assets.open("zonas_risco.csv").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("tipo") }
                .mapNotNull { l ->
                    val c = l.split(';')
                    if (c.size < 5) return@mapNotNull null
                    val lat = c[1].trim().toDoubleOrNull() ?: return@mapNotNull null
                    val lon = c[2].trim().toDoubleOrNull() ?: return@mapNotNull null
                    val rad = c[3].trim().toDoubleOrNull() ?: return@mapNotNull null
                    UrbanZone(c[0].trim() == "D", GeoPoint(lat, lon), rad, c[4].trim())
                }.toList()
        }
    }

    /** Peso multiplicador atual: mais forte à noite e no fim de semana. */
    fun factorNow(now: LocalDateTime = LocalDateTime.now()): Double {
        val h = now.hour
        val night = h >= 19 || h < 6
        val weekend = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY ||
            (now.dayOfWeek == DayOfWeek.FRIDAY && h >= 18)
        return (if (night) 1.6 else 1.0) * (if (weekend) 1.3 else 1.0)
    }

    /** Índice espacial rápido em grade para o BRouter. */
    fun index(
        list: List<Corridor>,
        modeFactor: Double,
        zoneList: List<UrbanZone> = emptyList(),
        now: LocalDateTime = LocalDateTime.now(),
    ): btools.router.CorridorIndex {
        val ix = btools.router.CorridorIndex(HALF_WIDTH_M)
        val time = factorNow(now)
        for (z in zoneList) {
            val w = (if (z.dense) DENSE_WEIGHT * time else METRO_WEIGHT) * modeFactor
            ix.addArea(z.point.lat, z.point.lon, z.radiusM, w, z.dense)
        }
        for (c in list) {
            val w = c.weight * modeFactor * (if (c.worseAtNight) time else 1.0)
            val wr = c.regionWeight * modeFactor * (if (c.worseAtNight) time else 1.0)
            for (l in c.lines) {
                val la = DoubleArray(l.size) { l[it].lat }
                val lo = DoubleArray(l.size) { l[it].lon }
                ix.addLine(la, lo, w)
                ix.addRegion(la, lo, 1500.0, wr)
            }
        }
        return ix
    }

    /** Trechos perigosos que a rota percorre de verdade (ao menos 500 m seguidos). */
    fun hits(route: RouteResult, list: List<Corridor>): List<CorridorHit> {
        val pts = route.points
        if (pts.size < 2) return emptyList()
        val out = ArrayList<CorridorHit>()
        for (c in list) {
            val pad = 0.002
            var start = -1
            var lastIn = -1
            for (i in pts.indices) {
                val p = pts[i]
                val inBox = p.lat in (c.minLat - pad)..(c.maxLat + pad) && p.lon in (c.minLon - pad)..(c.maxLon + pad)
                val inside = inBox && c.probe.weightAt(p.lat, p.lon) > 0
                if (inside) {
                    if (start < 0) start = i
                    lastIn = i
                } else if (start >= 0 && route.cumDist[i] - route.cumDist[lastIn] > 300) {
                    addHit(out, c, route, start, lastIn)
                    start = -1
                }
            }
            if (start >= 0) addHit(out, c, route, start, lastIn)
        }
        val sorted = out.sortedByDescending { it.lengthM }
        val kept = ArrayList<CorridorHit>()
        for (h in sorted) {
            val overlapped = kept.any { k ->
                val ov = minOf(k.toM, h.toM) - maxOf(k.fromM, h.fromM)
                ov > h.lengthM * 0.5
            }
            if (!overlapped) kept.add(h)
        }
        return kept.sortedBy { it.fromM }
    }

    private fun addHit(out: MutableList<CorridorHit>, c: Corridor, route: RouteResult, a: Int, b: Int) {
        val from = route.cumDist[a]
        val to = route.cumDist[b]
        if (to - from >= 500) out.add(CorridorHit(c, from, to))
    }
}

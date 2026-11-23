package com.motorota.data

import android.content.Context
import com.motorota.routing.GeoPoint
import com.motorota.routing.RouteResult
import org.json.JSONArray
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Trecho de rodovia/via expressa perigoso (assaltos): ex. Washington Luis, Rodoanel.
 * Diferente das areas (comunidades), aqui quem "paga" e a PROPRIA rodovia:
 * a rota procura um caminho alternativo razoavel; se nao tiver, passa e avisa.
 */
data class Corridor(
    val name: String,
    val city: String,
    val reason: String,
    /** custo extra por metro rodado no trecho (modo Desempenho, de dia, dia de semana) */
    val weight: Double,
    val worseAtNight: Boolean,
    val lines: List<List<GeoPoint>>,
    /** custo extra por metro nas RUAS LOCAIS em volta (~1,5 km): nao trocar a rodovia pelos bairros vizinhos */
    val regionWeight: Double = 0.0,
) {
    /** indice rapido (peso 1) para testar se um ponto da rota esta em cima deste trecho */
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

/** Um trecho perigoso que a rota percorre: de onde ate onde (metros desde o inicio). */
data class CorridorHit(val corridor: Corridor, val fromM: Double, val toM: Double) {
    val lengthM get() = toM - fromM
}

/** Zona do calculo de rota: M = regiao metropolitana (so ruas pagam), D = area urbana densa (todas pagam). */
data class UrbanZone(val dense: Boolean, val point: GeoPoint, val radiusM: Double, val name: String)

object Corridors {
    @Volatile var all: List<Corridor> = emptyList(); private set
    @Volatile var zones: List<UrbanZone> = emptyList(); private set

    /** peso por metro (modo Desempenho, de dia): ruas na regiao metropolitana / qualquer via em area densa */
    private const val METRO_WEIGHT = 1.0
    private const val DENSE_WEIGHT = 4.0

    /** meia-largura da faixa em volta do eixo de cada pista (pega a pista e as marginais coladas) */
    private const val HALF_WIDTH_M = 35.0

    fun load(ctx: Context) {
        val arr = JSONArray(ctx.assets.open("trechos_risco.json").bufferedReader().use { it.readText() })
        val out = ArrayList<Corridor>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ls = o.getJSONArray("linhas")
            val lines = (0 until ls.length()).map { j ->
                val l = ls.getJSONArray(j)
                (0 until l.length()).map { k -> l.getJSONArray(k).let { GeoPoint(it.getDouble(0), it.getDouble(1)) } }
            }
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
                    if (c.size < 5) null
                    else UrbanZone(c[0].trim() == "D", GeoPoint(c[1].toDouble(), c[2].toDouble()), c[3].toDouble(), c[4].trim())
                }.toList()
        }
    }

    /** Peso de verdade agora: mais forte a noite e no fim de semana (quando ha mais assalto a moto). */
    fun factorNow(now: LocalDateTime = LocalDateTime.now()): Double {
        val h = now.hour
        val night = h >= 19 || h < 6
        val weekend = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY ||
            (now.dayOfWeek == DayOfWeek.FRIDAY && h >= 18)
        return (if (night) 1.6 else 1.0) * (if (weekend) 1.3 else 1.0)
    }

    /** Indice rapido (grade) para o BRouter: cada metro rodado dentro da faixa paga o peso do trecho. */
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

    /**
     * (nao usado: era bem mais lento que o indice) Poligonos para o BRouter ("lon,lat,lon,lat,...,peso|..."): uma faixa fechada em volta
     * de cada pedaco de ~3 km do eixo da rodovia. Quem so CRUZA a rodovia paga ~70 m; quem anda nela paga tudo.
     */
    fun brouterPolygons(list: List<Corridor>, modeFactor: Double, now: LocalDateTime = LocalDateTime.now()): String {
        val sb = StringBuilder()
        val time = factorNow(now)
        for (c in list) {
            val w = c.weight * modeFactor * (if (c.worseAtNight) time else 1.0)
            for (line in c.lines) {
                for (chunk in chunks(line, 3000.0)) {
                    val poly = buffer(chunk, HALF_WIDTH_M) ?: continue
                    if (sb.isNotEmpty()) sb.append('|')
                    for (p in poly) sb.append("%.6f,%.6f,".format(java.util.Locale.US, p.lon, p.lat))
                    sb.append("%.3f".format(java.util.Locale.US, w))
                }
            }
        }
        return sb.toString()
    }

    /** Trechos perigosos que a rota percorre de verdade (pelo menos 500 m seguidos, para nao contar cruzamentos). */
    fun hits(route: RouteResult, list: List<Corridor>): List<CorridorHit> {
        val pts = route.points
        if (pts.size < 2) return emptyList()
        val out = ArrayList<CorridorHit>()
        for (c in list) {
            // descarte rapido: rota longe da caixa do trecho
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
                    // saiu do trecho (tolera buracos curtos, ex.: viaduto/trevo)
                    addHit(out, c, route, start, lastIn)
                    start = -1
                }
            }
            if (start >= 0) addHit(out, c, route, start, lastIn)
        }
        // rodovias que dividem a mesma pista (ex.: BR-040 e BR-116 em Caxias): fica so o aviso do trecho maior
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

    // ---- geometria ----

    private fun distToLines(p: GeoPoint, lines: List<List<GeoPoint>>): Double {
        var best = Double.MAX_VALUE
        val k = cos(Math.toRadians(p.lat))
        for (l in lines) for (i in 0 until l.size - 1) {
            val a = l[i]; val b = l[i + 1]
            // descarte rapido (~1 km)
            if (p.lat < min(a.lat, b.lat) - 0.01 || p.lat > max(a.lat, b.lat) + 0.01) continue
            if (p.lon < min(a.lon, b.lon) - 0.01 || p.lon > max(a.lon, b.lon) + 0.01) continue
            val ax = (a.lon - p.lon) * k * 111320; val ay = (a.lat - p.lat) * 111320
            val bx = (b.lon - p.lon) * k * 111320; val by = (b.lat - p.lat) * 111320
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 > 0) (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0) else 0.0
            best = min(best, hypot(ax + dx * t, ay + dy * t))
        }
        return best
    }

    /** corta a linha em pedacos de ate maxLen metros (com 1 ponto de sobreposicao) */
    private fun chunks(line: List<GeoPoint>, maxLen: Double): List<List<GeoPoint>> {
        val out = ArrayList<List<GeoPoint>>()
        var cur = arrayListOf(line[0])
        var len = 0.0
        for (i in 1 until line.size) {
            len += line[i - 1].distanceTo(line[i])
            cur.add(line[i])
            if (len >= maxLen && i < line.size - 1) {
                out.add(cur)
                cur = arrayListOf(line[i])
                len = 0.0
            }
        }
        if (cur.size >= 2) out.add(cur)
        return out
    }

    /** faixa fechada em volta da linha: lado esquerdo indo + lado direito voltando */
    private fun buffer(line: List<GeoPoint>, half: Double): List<GeoPoint>? {
        if (line.size < 2) return null
        val left = ArrayList<GeoPoint>()
        val right = ArrayList<GeoPoint>()
        for (i in line.indices) {
            val p = line[i]
            val a = line[max(0, i - 1)]
            val b = line[min(line.size - 1, i + 1)]
            val k = cos(Math.toRadians(p.lat))
            // direcao media do trecho neste ponto, em metros
            var dx = (b.lon - a.lon) * k * 111320
            var dy = (b.lat - a.lat) * 111320
            val n = hypot(dx, dy)
            if (n == 0.0) continue
            dx /= n; dy /= n
            // normal (perpendicular), convertida de volta para graus
            val offLat = (dx * half) / 111320
            val offLon = (-dy * half) / (111320 * k)
            left.add(GeoPoint(p.lat + offLat, p.lon + offLon))
            right.add(GeoPoint(p.lat - offLat, p.lon - offLon))
        }
        if (left.size < 2) return null
        return left + right.reversed()
    }
}

package com.motorota.data

import com.motorota.BuildConfig
import com.motorota.routing.GeoPoint
import com.motorota.routing.RouteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Ocorrencia de transito em tempo real (TomTom) que esta no caminho. */
data class LiveIncident(
    val id: String,
    val emoji: String,
    val title: String,
    val speech: String,
    /** ponto do meio da ocorrencia (usado para avisar e para desviar) */
    val point: GeoPoint,
    val isClosure: Boolean,
    val where: String,
    /** linha inteira da ocorrência (para conferir se é a MESMA pista da rota) */
    val pts: List<GeoPoint> = emptyList(),
)

/**
 * Consulta a TomTom (plano gratis: 2.500 consultas por dia) para saber o que esta
 * acontecendo AGORA ao longo da rota: interdicoes, acidentes, obras, alagamentos...
 */
object Traffic {
    val enabled get() = Keys.tomtom.isNotBlank()

    /** a ultima consulta deixou algum pedaco da rota sem resposta? */
    @Volatile var partial = false; private set

    private const val FIELDS =
        "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay,events{description},from,to,roadNumbers}}}"

    /** Devolve as ocorrencias que ficam em cima da rota. Lanca excecao se nao tiver internet. */
    suspend fun along(route: RouteResult): List<LiveIncident> = withContext(Dispatchers.IO) {
        if (!enabled || route.points.size < 2) return@withContext emptyList()
        val found = LinkedHashMap<String, LiveIncident>()
        val boxes = boxes(route.points)
        var ok = 0
        var lastError: Exception? = null
        for (box in boxes) {
            // cada pedaco tenta 2 vezes; se um falhar, os outros continuam valendo
            val json = try {
                fetch(box)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                try {
                    kotlinx.coroutines.delay(1500)
                    fetch(box)
                } catch (e2: kotlinx.coroutines.CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    lastError = e2
                    android.util.Log.w("MotoRota", "TomTom pedaco falhou: $e2")
                    continue
                }
            }
            ok++
            val arr = JSONObject(json).optJSONArray("incidents") ?: continue
            for (i in 0 until arr.length()) {
                val inc = parse(arr.getJSONObject(i)) ?: continue
                if (inc.id in found) continue
                if (distToRoute(route.points, inc.point) > 20.0) continue
                if (inc.isClosure && !sameRoad(route.points, inc.pts)) {
                    // interdição numa pista vizinha (ex.: a pista velha da Serra das Araras, embaixo dos
                    // viadutos novos) não fecha a NOSSA pista: não desvia por causa dela
                    Http.report("interdicao", "ignorada (pista vizinha) em ${"%.5f,%.5f".format(java.util.Locale.US, inc.point.lat, inc.point.lon)} ${inc.where}")
                    continue
                }
                if (inc.isClosure) Http.report("interdicao", "na rota em ${"%.5f,%.5f".format(java.util.Locale.US, inc.point.lat, inc.point.lon)} ${inc.where}")
                found[inc.id] = inc
            }
        }
        if (ok == 0 && lastError != null) throw lastError!!
        partial = ok < boxes.size
        found.values.toList()
    }

    private fun fetch(b: DoubleArray): String {
        val url = "https://api.tomtom.com/traffic/services/5/incidentDetails" +
            "?key=" + Keys.tomtom +
            "&bbox=" + "%.5f,%.5f,%.5f,%.5f".format(java.util.Locale.US, b[0], b[1], b[2], b[3]) +
            "&fields=" + URLEncoder.encode(FIELDS, "UTF-8") +
            "&language=pt-PT"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
        conn.setRequestProperty("Accept-Encoding", "gzip")
        try {
            if (conn.responseCode != 200) throw java.io.IOException("TomTom respondeu ${conn.responseCode}")
            val raw = conn.inputStream
            val input = if (conn.contentEncoding.equals("gzip", true)) java.util.zip.GZIPInputStream(raw) else raw
            return input.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(o: JSONObject): LiveIncident? {
        val p = o.optJSONObject("properties") ?: return null
        val cat = p.optInt("iconCategory", 0)
        val magnitude = p.optInt("magnitudeOfDelay", 0)
        val (emoji, title, speech) = when (cat) {
            1 -> Triple("💥", "Acidente", "acidente na pista")
            2 -> Triple("🌫️", "Neblina", "neblina")
            3 -> Triple("⚠️", "Perigo na pista", "perigo na pista")
            4 -> Triple("🌧️", "Chuva forte", "chuva forte")
            5 -> Triple("🧊", "Pista escorregadia", "pista escorregadia")
            6 -> if (magnitude >= 3) Triple("🚗", "Trânsito parado", "trânsito parado") else return null
            7 -> Triple("🚧", "Faixa interditada", "faixa interditada")
            8 -> Triple("⛔", "Via interditada", "via interditada")
            9 -> Triple("🚧", "Obras", "obras na pista")
            10 -> Triple("💨", "Vento forte", "vento forte")
            11 -> Triple("🌊", "Alagamento", "alagamento na pista")
            14 -> Triple("🚙", "Veículo parado", "veículo parado na pista")
            else -> return null
        }
        val geom = o.optJSONObject("geometry") ?: return null
        val coords = geom.optJSONArray("coordinates") ?: return null
        val pts = ArrayList<GeoPoint>()
        if (geom.optString("type") == "Point") {
            pts.add(GeoPoint(coords.getDouble(1), coords.getDouble(0)))
        } else {
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                pts.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
            }
        }
        if (pts.isEmpty()) return null
        val roads = p.optJSONArray("roadNumbers")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val where = roads.firstOrNull() ?: p.optString("from").takeIf { it.isNotBlank() && it != "null" } ?: ""
        return LiveIncident(
            id = p.optString("id").ifBlank { "${pts[0].lat},${pts[0].lon},$cat" },
            emoji = emoji,
            title = title,
            speech = speech,
            point = midpoint(pts),
            // magnitude 4 = via fechada, mesmo quando a categoria e outra
            isClosure = cat == 8 || magnitude == 4,
            where = where,
            pts = pts,
        )
    }

    /** ponto que fica na metade do comprimento da linha (uma rua que so CRUZA a rota nao bate no meio) */
    private fun midpoint(pts: List<GeoPoint>): GeoPoint {
        if (pts.size == 1) return pts[0]
        var total = 0.0
        for (i in 1 until pts.size) total += pts[i - 1].distanceTo(pts[i])
        var acc = 0.0
        for (i in 1 until pts.size) {
            val d = pts[i - 1].distanceTo(pts[i])
            if (acc + d >= total / 2 && d > 0) {
                val t = (total / 2 - acc) / d
                return GeoPoint(
                    pts[i - 1].lat + (pts[i].lat - pts[i - 1].lat) * t,
                    pts[i - 1].lon + (pts[i].lon - pts[i - 1].lon) * t,
                )
            }
            acc += d
        }
        return pts.last()
    }

    /** divide a rota em retangulos de ate ~8.000 km2 (limite da TomTom: 10.000 km2) */
    private fun boxes(pts: List<GeoPoint>): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        val pad = 0.005
        fun area(a: Double, b: Double, c: Double, d: Double): Double {
            val h = (b - a + 2 * pad) * 111.0
            val w = (d - c + 2 * pad) * 111.0 * cos(Math.toRadians((a + b) / 2))
            return h * w
        }
        for (p in pts) {
            val nMinLat = min(minLat, p.lat); val nMaxLat = max(maxLat, p.lat)
            val nMinLon = min(minLon, p.lon); val nMaxLon = max(maxLon, p.lon)
            if (minLat <= maxLat && area(nMinLat, nMaxLat, nMinLon, nMaxLon) > 8000) {
                out.add(doubleArrayOf(minLon - pad, minLat - pad, maxLon + pad, maxLat + pad))
                minLat = p.lat; maxLat = p.lat; minLon = p.lon; maxLon = p.lon
            } else {
                minLat = nMinLat; maxLat = nMaxLat; minLon = nMinLon; maxLon = nMaxLon
            }
        }
        if (minLat <= maxLat) out.add(doubleArrayOf(minLon - pad, minLat - pad, maxLon + pad, maxLat + pad))
        return out
    }

    /**
     * A interdição está mesmo na pista da rota? Quase toda a linha dela tem que estar colada na rota
     * (até 12 m) e no MESMO sentido. Pista paralela, pista contrária ou pista velha ao lado não contam.
     */
    private fun sameRoad(route: List<GeoPoint>, inc: List<GeoPoint>): Boolean {
        if (inc.size < 2) return distToRoute(route, inc.firstOrNull() ?: return false) <= 8.0
        var near = 0; var aligned = 0; var segs = 0
        for (i in inc.indices) if (distToRoute(route, inc[i]) <= 12.0) near++
        for (i in 1 until inc.size) {
            val a = inc[i - 1]; val b = inc[i]
            if (a.distanceTo(b) < 15) continue
            segs++
            val rb = routeBearingNear(route, GeoPoint((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)) ?: continue
            var d = Math.abs(bearing(a, b) - rb) % 360
            if (d > 180) d = 360 - d
            if (d < 35) aligned++
        }
        val nearOk = near >= Math.ceil(inc.size * 0.8)
        val dirOk = segs == 0 || aligned >= Math.ceil(segs * 0.7)
        return nearOk && dirOk
    }

    private fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val y = (b.lon - a.lon) * cos(Math.toRadians(a.lat))
        val x = b.lat - a.lat
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }

    private fun routeBearingNear(route: List<GeoPoint>, p: GeoPoint): Double? {
        var best = Double.MAX_VALUE; var at = -1
        for (i in 0 until route.size - 1) {
            val d = distToRoute(listOf(route[i], route[i + 1]), p)
            if (d < best) { best = d; at = i }
        }
        if (at < 0 || best > 30) return null
        return bearing(route[at], route[at + 1])
    }

    private fun distToRoute(pts: List<GeoPoint>, p: GeoPoint): Double {
        val k = cos(Math.toRadians(p.lat))
        var best = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            // descarte rapido: longe demais dos dois pontos do trecho
            if (Math.abs(a.lat - p.lat) > 0.05 && Math.abs(b.lat - p.lat) > 0.05) continue
            val ax = (a.lon - p.lon) * k * 111320; val ay = (a.lat - p.lat) * 111320
            val bx = (b.lon - p.lon) * k * 111320; val by = (b.lat - p.lat) * 111320
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val x = ax + dx * t; val y = ay + dy * t
            best = min(best, Math.sqrt(x * x + y * y))
        }
        return best
    }
}

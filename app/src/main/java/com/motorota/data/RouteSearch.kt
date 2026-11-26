package com.motorota.data

import com.motorota.routing.GeoPoint
import com.motorota.routing.RouteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** Um lugar encontrado AO LONGO da rota: em que km da rota fica e quanto sai dela. */
data class RoutePlace(val place: Place, val alongM: Double, val offM: Double)

/**
 * Procura postos, restaurantes, farmácias... em cima do caminho (e não perto de onde você está agora).
 * - TomTom "Search Along Route": a melhor, já considera o desvio de verdade (precisa da chave).
 * - Mapa aberto (OpenStreetMap/Photon): procura em vários pontos da rota à frente.
 * Os dois rodam juntos e os resultados são somados.
 */
object RouteSearch {

    /** palavra falada -> etiqueta do OpenStreetMap */
    private val OSM_TAGS = listOf(
        "posto" to "amenity:fuel", "combustivel" to "amenity:fuel", "gasolina" to "amenity:fuel", "abastecer" to "amenity:fuel",
        "restaurante" to "amenity:restaurant", "almoc" to "amenity:restaurant", "comer" to "amenity:restaurant",
        "lanchonete" to "amenity:fast_food", "lanche" to "amenity:fast_food",
        "farmacia" to "amenity:pharmacy", "drogaria" to "amenity:pharmacy",
        "padaria" to "shop:bakery", "mercado" to "shop:supermarket", "supermercado" to "shop:supermarket",
        "borracharia" to "shop:tyres", "pneu" to "shop:tyres",
        "hotel" to "tourism:hotel", "pousada" to "tourism:guest_house",
        "hospital" to "amenity:hospital", "banheiro" to "amenity:toilets",
        "cafe" to "amenity:cafe", "bar" to "amenity:bar", "caixa eletronico" to "amenity:atm", "banco" to "amenity:bank",
        "oficina" to "shop:motorcycle_repair", "mecanic" to "shop:motorcycle_repair",
    )

    fun osmTag(query: String): String? {
        val q = Geocoder.norm(query)
        return OSM_TAGS.firstOrNull { q.contains(it.first) }?.second
    }

    /** O pedido é de um TIPO de lugar (posto, restaurante...) e não de um endereço específico? */
    fun isCategory(query: String) = osmTag(query) != null

    suspend fun alongRoute(route: RouteResult, fromM: Double, query: String): List<RoutePlace> = withContext(Dispatchers.IO) {
        if (route.points.size < 2) return@withContext emptyList()
        val tomtom = async { runCatchingIO { if (Keys.tomtom.isNotBlank()) tomtomAlong(route, fromM, query) else emptyList() } }
        val osm = async { runCatchingIO { osmAlong(route, fromM, query) } }
        val all = (tomtom.await() + osm.await())
            .filter { it.alongM >= fromM - 300 && it.offM < 1200 }
        // remove o mesmo lugar vindo das duas fontes
        val out = ArrayList<RoutePlace>()
        for (p in all.sortedBy { it.offM }) {
            if (out.none { it.place.point.distanceTo(p.place.point) < 120 }) out.add(p)
        }
        out.sortedBy { it.alongM }
    }

    private suspend fun <T> runCatchingIO(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Http.report("busca-rota", "${e.message}")
        emptyList()
    }

    /** Pontos da rota a partir de fromM, de `stepM` em `stepM` metros (no máximo `max`). */
    private fun samples(route: RouteResult, fromM: Double, stepM: Double, max: Int): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        var next = fromM
        for (i in route.points.indices) {
            if (route.cumDist[i] >= next) {
                out.add(route.points[i])
                next = route.cumDist[i] + stepM
                if (out.size >= max) break
            }
        }
        if (out.isEmpty()) out.add(route.points.last())
        return out
    }

    private suspend fun osmAlong(route: RouteResult, fromM: Double, query: String): List<RoutePlace> = coroutineScope {
        val remaining = (route.cumDist.last() - fromM).coerceAtLeast(1.0)
        // até 12 pontos espalhados pelo que falta da rota (mínimo 3 km entre eles)
        val step = maxOf(3000.0, remaining / 12)
        val tag = osmTag(query)
        val q = URLEncoder.encode(query, "UTF-8")
        val pts = samples(route, fromM, step, 12)
        pts.map { p ->
            async {
                runCatching {
                    val url = "https://photon.komoot.io/api/?q=$q&limit=8&lat=${p.lat}&lon=${p.lon}&zoom=14&location_bias_scale=0.1" +
                        (tag?.let { "&osm_tag=$it" } ?: "")
                    val r = Http.request(url, readTimeoutMs = 10_000)
                    if (!r.ok) return@runCatching emptyList<RoutePlace>()
                    val feats = JSONObject(r.text).optJSONArray("features") ?: JSONArray()
                    (0 until feats.length()).mapNotNull { i ->
                        val f = feats.getJSONObject(i)
                        val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                        val pr = f.getJSONObject("properties")
                        val pt = GeoPoint(c.getDouble(1), c.getDouble(0))
                        val name = pr.optString("name").ifBlank { pr.optString("brand") }.ifBlank { defaultName(query) }
                        val detail = listOf(pr.optString("street"), pr.optString("district"), pr.optString("city"))
                            .filter { it.isNotBlank() }.distinct().joinToString(" · ")
                        project(route, pt)?.let { (along, off) -> RoutePlace(Place(name, detail, pt), along, off) }
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    private fun defaultName(query: String) = when (osmTag(query)) {
        "amenity:fuel" -> "Posto de combustível"
        "amenity:restaurant" -> "Restaurante"
        "amenity:pharmacy" -> "Farmácia"
        else -> query.replaceFirstChar { it.uppercase() }
    }

    /** TomTom Search Along Route: considera o desvio real (até 15 min fora da rota). */
    private fun tomtomAlong(route: RouteResult, fromM: Double, query: String): List<RoutePlace> {
        val pts = samples(route, fromM, maxOf(200.0, (route.cumDist.last() - fromM) / 140), 150)
        if (pts.size < 2) return emptyList()
        val body = JSONObject().put(
            "route",
            JSONObject().put("points", JSONArray().apply { pts.forEach { put(JSONObject().put("lat", it.lat).put("lon", it.lon)) } }),
        )
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val url = "https://api.tomtom.com/search/2/searchAlongRoute/$q.json?key=${Keys.tomtom}" +
            "&maxDetourTime=900&limit=20&language=pt-BR&spreadingMode=auto"
        val r = Http.request(url, "POST", body = body.toString(), readTimeoutMs = 12_000)
        if (!r.ok) {
            Http.report("busca-rota", "tomtom ${r.code} ${r.reason()} ${r.text.take(200)}")
            return emptyList()
        }
        val res = JSONObject(r.text).optJSONArray("results") ?: return emptyList()
        return (0 until res.length()).mapNotNull { i ->
            val o = res.getJSONObject(i)
            val pos = o.optJSONObject("position") ?: return@mapNotNull null
            val pt = GeoPoint(pos.getDouble("lat"), pos.getDouble("lon"))
            val addr = o.optJSONObject("address") ?: JSONObject()
            val name = o.optJSONObject("poi")?.optString("name").orEmpty().ifBlank { defaultName(query) }
            val detail = listOf(addr.optString("streetName"), addr.optString("municipalitySubdivision"), addr.optString("municipality"))
                .filter { it.isNotBlank() }.distinct().joinToString(" · ")
            project(route, pt)?.let { (along, off) -> RoutePlace(Place(name, detail, pt), along, off) }
        }
    }

    /** (metros desde o início da rota, distância até a rota) do ponto mais próximo da linha. */
    fun project(route: RouteResult, p: GeoPoint): Pair<Double, Double>? {
        val pts = route.points
        val k = cos(Math.toRadians(p.lat))
        var best = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            if (abs(a.lat - p.lat) > 0.05 && abs(b.lat - p.lat) > 0.05) continue
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
                bestAlong = route.cumDist[i] + (route.cumDist[i + 1] - route.cumDist[i]) * t
            }
        }
        return if (best == Double.MAX_VALUE) null else bestAlong to best
    }

    /**
     * Escolhe o melhor lugar conforme "onde" o piloto quer parar:
     * próximo / meio do caminho / perto do destino / "km 40".
     */
    fun pick(list: List<RoutePlace>, fromM: Double, totalM: Double, where: String?): RoutePlace? {
        if (list.isEmpty()) return null
        val w = Geocoder.norm(where.orEmpty())
        val km = Regex("(\\d+)\\s*(km|quilometro)").find(w)?.groupValues?.get(1)?.toDoubleOrNull()
        val target = when {
            km != null -> fromM + km * 1000
            w.contains("meio") || w.contains("metade") -> fromM + (totalM - fromM) / 2
            w.contains("destino") || w.contains("chegando") || w.contains("final") -> totalM - 5000
            else -> fromM // o próximo
        }
        // perto do ponto desejado, penalizando quem sai muito da rota (1 m fora ~ 8 m de estrada)
        return list.minByOrNull { abs(it.alongM - target) + it.offM * 8 }
    }
}

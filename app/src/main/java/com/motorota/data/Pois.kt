package com.motorota.data

import com.motorota.routing.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.floor

/**
 * Estabelecimentos do mapa (postos, restaurantes, farmácias, mercados, hotéis, hospitais) vindos da
 * TomTom, que é atualizada com frequência. Os do mapa aberto ficam escondidos porque muitos são antigos.
 * Guarda o que já buscou por 12 horas para gastar pouco do limite grátis (2.500 por dia).
 */
object Pois {
    data class Poi(val name: String, val kind: Kind, val point: GeoPoint)

    enum class Kind(val label: String, val color: String) {
        POSTO("Posto", "#F59E0B"),
        RESTAURANTE("Restaurante", "#EF4444"),
        FARMACIA("Farmácia", "#10B981"),
        MERCADO("Mercado", "#8B5CF6"),
        HOTEL("Hotel", "#3B82F6"),
        HOSPITAL("Hospital", "#EC4899"),
    }

    // códigos de categoria da TomTom
    private val CATS = mapOf(
        7311 to Kind.POSTO, 7315 to Kind.RESTAURANTE, 7326 to Kind.FARMACIA,
        7332 to Kind.MERCADO, 7314 to Kind.HOTEL, 7321 to Kind.HOSPITAL,
    )

    private val cache = HashMap<String, Pair<Long, List<Poi>>>()

    /** Estabelecimentos num raio (até 3 km) em volta do ponto. Lista vazia sem chave da TomTom. */
    suspend fun around(center: GeoPoint, radiusM: Int): List<Poi> = withContext(Dispatchers.IO) {
        if (Keys.tomtom.isBlank()) return@withContext emptyList()
        val r = radiusM.coerceIn(500, 3000)
        // grade de ~1 km: andar um pouco no mapa reaproveita a busca anterior
        val key = "${floor(center.lat * 100)}:${floor(center.lon * 100)}:${r / 1000}"
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[key] }?.let { (t, list) -> if (now - t < 12 * 3_600_000L) return@withContext list }
        val url = "https://api.tomtom.com/search/2/nearbySearch/.json?key=${Keys.tomtom}" +
            "&lat=${center.lat}&lon=${center.lon}&radius=$r&limit=100&language=pt-BR" +
            "&categorySet=" + CATS.keys.joinToString(",")
        val res = Http.request(url, readTimeoutMs = 10_000)
        if (!res.ok) {
            Http.report("pois", "${res.code} ${res.reason()}")
            return@withContext emptyList()
        }
        val arr = JSONObject(res.text).optJSONArray("results") ?: return@withContext emptyList()
        val out = ArrayList<Poi>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pos = o.optJSONObject("position") ?: continue
            val poi = o.optJSONObject("poi") ?: continue
            val catIds = poi.optJSONArray("categorySet")
            var kind: Kind? = null
            if (catIds != null) for (j in 0 until catIds.length()) {
                val id = catIds.optJSONObject(j)?.optInt("id") ?: continue
                kind = CATS[id] ?: CATS[id / 1000] ?: CATS.entries.firstOrNull { id.toString().startsWith(it.key.toString()) }?.value
                if (kind != null) break
            }
            out.add(Poi(poi.optString("name").ifBlank { kind?.label ?: "Local" }, kind ?: Kind.MERCADO, GeoPoint(pos.getDouble("lat"), pos.getDouble("lon"))))
        }
        synchronized(cache) {
            cache[key] = now to out
            if (cache.size > 60) cache.remove(cache.keys.first())
        }
        out
    }
}

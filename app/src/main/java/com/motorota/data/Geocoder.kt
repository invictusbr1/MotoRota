package com.motorota.data

import com.motorota.routing.GeoPoint
import com.motorota.routing.formatDistance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

data class Place(val name: String, val detail: String, val point: GeoPoint)

/**
 * Busca de endereços e lugares (hotel, praça, posto, loja...).
 *
 * - TomTom Search (quando há chave): muito melhor para estabelecimentos e nomes
 *   populares, entende erro de digitação e prioriza o que está perto.
 * - Photon (OpenStreetMap, grátis, sem chave): sempre consultado junto.
 *
 * Os dois rodam ao mesmo tempo; os resultados são juntos sem repetir o mesmo lugar.
 */
object Geocoder {
    suspend fun search(query: String, near: GeoPoint?): List<Place> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList<Place>()
        coroutineScope {
            val tt = async {
                if (Keys.tomtom.isNotBlank()) runSafe { tomtom(q, near) } else Result.success(emptyList())
            }
            val ph = async { runSafe { photon(q, near) } }
            val a = tt.await()
            val b = ph.await()
            if (a.isFailure && b.isFailure) throw (b.exceptionOrNull() ?: Exception("sem internet"))
            merge(a.getOrDefault(emptyList()) + b.getOrDefault(emptyList()))
                .map { withDistance(it, near) }
                .take(12)
        }
    }

    /** Nome do lugar onde o ponto está: "Rua X, Bairro, Cidade". null se não conseguir. */
    suspend fun reverse(p: GeoPoint): String? = withContext(Dispatchers.IO) {
        if (Keys.tomtom.isNotBlank()) {
            val viaTomTom = runSafe {
                val url = "https://api.tomtom.com/search/2/reverseGeocode/${p.lat},${p.lon}.json" +
                    "?key=${Keys.tomtom}&language=pt-BR"
                val a = JSONObject(get(url)).optJSONArray("addresses")?.optJSONObject(0)?.optJSONObject("address")
                a?.let {
                    listOf(it.optString("streetName"), it.optString("municipalitySubdivision"), it.optString("municipality"))
                        .filter { s -> s.isNotBlank() }.distinct().joinToString(", ")
                }
            }.getOrNull()
            if (!viaTomTom.isNullOrBlank()) return@withContext viaTomTom
        }
        runSafe {
            val url = "https://photon.komoot.io/reverse?lat=${p.lat}&lon=${p.lon}&limit=1"
            val f = JSONObject(get(url)).optJSONArray("features")?.optJSONObject(0)?.optJSONObject("properties")
            f?.let {
                listOf(
                    it.optString("street").ifBlank { it.optString("name") },
                    it.optString("district"),
                    it.optString("city"),
                ).filter { s -> s.isNotBlank() }.distinct().joinToString(", ")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private inline fun <T> runSafe(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Remove o mesmo lugar vindo das duas fontes (mesmo nome a menos de 150 m, ou praticamente o mesmo ponto). */
    private fun merge(all: List<Place>): List<Place> {
        val out = ArrayList<Place>()
        for (p in all) {
            val dup = out.any { o ->
                val d = o.point.distanceTo(p.point)
                d < 25 || (d < 150 && norm(o.name) == norm(p.name))
            }
            if (!dup) out.add(p)
        }
        return out
    }

    private fun withDistance(p: Place, near: GeoPoint?): Place {
        if (near == null) return p
        val d = formatDistance(near.distanceTo(p.point))
        return p.copy(detail = if (p.detail.isBlank()) d else "$d · ${p.detail}")
    }

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 9_000
        conn.readTimeout = 9_000
        conn.setRequestProperty("User-Agent", "MotoRota-Android/0.2")
        try {
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** TomTom Fuzzy Search: estabelecimentos (POI), endereços, ruas e bairros. */
    private fun tomtom(q: String, near: GeoPoint?): List<Place> {
        val enc = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}" } ?: ""
        val url = "https://api.tomtom.com/search/2/search/$enc.json?key=${Keys.tomtom}" +
            "&countrySet=BR&language=pt-BR&limit=10&typeahead=true$bias"
        val res = JSONObject(get(url)).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until res.length()) {
            val r = res.getJSONObject(i)
            val pos = r.optJSONObject("position") ?: continue
            val addr = r.optJSONObject("address") ?: JSONObject()
            val poi = r.optJSONObject("poi")
            val poiName = poi?.optString("name").orEmpty()
            val street = addr.optString("streetName")
            val number = addr.optString("streetNumber")
            val name = when {
                poiName.isNotBlank() -> poiName
                r.optString("type") == "Geography" ->
                    addr.optString("municipalitySubdivision")
                        .ifBlank { addr.optString("municipality") }
                        .ifBlank { addr.optString("freeformAddress") }
                street.isNotBlank() -> listOf(street, number).filter { it.isNotBlank() }.joinToString(", ")
                else -> addr.optString("freeformAddress")
            }
            if (name.isBlank()) continue
            val category = poi?.optJSONArray("categories")?.optString(0).orEmpty()
            val detail = listOf(
                category.replaceFirstChar { it.uppercase() },
                if (poiName.isNotBlank()) street else "",
                addr.optString("municipalitySubdivision"),
                addr.optString("municipality"),
                addr.optString("countrySubdivisionCode").ifBlank { addr.optString("countrySubdivision") },
            ).filter { it.isNotBlank() && !it.equals(name, ignoreCase = true) }.distinct().joinToString(" · ")
            out.add(Place(name, detail, GeoPoint(pos.getDouble("lat"), pos.getDouble("lon"))))
        }
        return out
    }

    /** Photon (OpenStreetMap). location_bias_scale baixo = dá mais peso ao que está perto de você. */
    private fun photon(q: String, near: GeoPoint?): List<Place> {
        val enc = URLEncoder.encode(q, "UTF-8")
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}&zoom=12&location_bias_scale=0.2" } ?: ""
        // limita a America do Sul (bbox: minLon,minLat,maxLon,maxLat)
        val url = "https://photon.komoot.io/api/?q=$enc&limit=12$bias&bbox=-82,-56,-34,13"
        val feats = JSONObject(get(url)).getJSONArray("features")
        val out = ArrayList<Place>()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.getJSONObject("properties")
            val street = p.optString("street").takeIf { it.isNotBlank() }
            val number = p.optString("housenumber").takeIf { it.isNotBlank() }
            val name = p.optString("name").takeIf { it.isNotBlank() }
                ?: listOfNotNull(street, number).joinToString(", ").takeIf { it.isNotBlank() }
                ?: continue
            val detail = listOfNotNull(
                if (p.optString("name").isNotBlank()) street else null,
                p.optString("district").takeIf { it.isNotBlank() },
                p.optString("city").takeIf { it.isNotBlank() },
                p.optString("state").takeIf { it.isNotBlank() },
            ).filter { !it.equals(name, ignoreCase = true) }.distinct().joinToString(" · ")
            out.add(Place(name, detail, GeoPoint(c.getDouble(1), c.getDouble(0))))
        }
        return out
    }
}

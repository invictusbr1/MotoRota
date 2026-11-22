package com.motorota.data

import com.motorota.routing.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Place(val name: String, val detail: String, val point: GeoPoint)

/**
 * Busca de enderecos e lugares usando o Photon (photon.komoot.io),
 * servico gratuito baseado no OpenStreetMap. Precisa de internet.
 */
object Geocoder {
    suspend fun search(query: String, near: GeoPoint?): List<Place> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}" } ?: ""
        // limita a America do Sul (bbox: minLon,minLat,maxLon,maxLat)
        val url = URL("https://photon.komoot.io/api/?q=$q&limit=10$bias&bbox=-82,-56,-34,13")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "MotoRota-Android/0.1")
        try {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val feats = JSONObject(text).getJSONArray("features")
            (0 until feats.length()).mapNotNull { i ->
                val f = feats.getJSONObject(i)
                val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                val p = f.getJSONObject("properties")
                val street = p.optString("street").takeIf { it.isNotBlank() }
                val number = p.optString("housenumber").takeIf { it.isNotBlank() }
                val name = p.optString("name").takeIf { it.isNotBlank() }
                    ?: listOfNotNull(street, number).joinToString(", ").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val detail = listOfNotNull(
                    if (p.optString("name").isNotBlank()) street else null,
                    p.optString("district").takeIf { it.isNotBlank() },
                    p.optString("city").takeIf { it.isNotBlank() },
                    p.optString("state").takeIf { it.isNotBlank() },
                    p.optString("country").takeIf { it.isNotBlank() },
                ).distinct().joinToString(" · ")
                Place(name, detail, GeoPoint(c.getDouble(1), c.getDouble(0)))
            }
        } finally {
            conn.disconnect()
        }
    }
}

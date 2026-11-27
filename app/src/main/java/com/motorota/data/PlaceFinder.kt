package com.motorota.data

import android.content.Context
import com.motorota.routing.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * Acha lugares que o mapa não conhece (loja antiga, concessionária, oficina, empresa do bairro):
 *
 * 1. Lugares já achados antes ficam guardados no celular: funcionam SEM internet e na hora.
 * 2. Busca normal do mapa (TomTom + OpenStreetMap).
 * 3. Se o mapa não tiver, pesquisa no Google (Gemini com Busca Google), que conhece praticamente
 *    todo comércio, e corrige erro de reconhecimento de voz ("Sabenalto" -> "Sabenauto").
 *    O endereço que o Google achar é convertido em ponto no mapa.
 */
class PlaceFinder(ctx: Context) {
    private val file = File(ctx.filesDir, "lugares_salvos.json")

    data class Saved(val name: String, val detail: String, val point: GeoPoint, val aliases: List<String>, val used: Long)

    private var saved: MutableList<Saved> = load()

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private val stop = setOf("de", "da", "do", "das", "dos", "a", "o", "e", "em", "na", "no", "rj", "rua", "av", "avenida")

    private fun words(s: String) = norm(s).split(' ').filter { it.length > 1 && it !in stop }.toSet()

    /** Procura nos lugares guardados (nome ou como o piloto já pediu antes). */
    fun findSaved(query: String): Saved? {
        val q = words(query)
        if (q.isEmpty()) return null
        var best: Saved? = null; var bestScore = 0.0
        for (s in saved) {
            for (cand in listOf(s.name) + s.aliases) {
                val w = words(cand)
                if (w.isEmpty()) continue
                val inter = q.intersect(w).size.toDouble()
                val score = inter / maxOf(q.size, w.size)
                if (score > bestScore) { bestScore = score; best = s }
            }
        }
        return if (bestScore >= 0.6) best else null
    }

    /** Guarda o lugar (e como o piloto falou) para achar de novo, mesmo offline. */
    fun remember(p: Place, spoken: String) {
        val key = norm(p.name)
        val old = saved.firstOrNull { norm(it.name) == key && it.point.distanceTo(p.point) < 300 }
        val aliases = ((old?.aliases ?: emptyList()) + spoken).map { it.trim() }.filter { it.isNotBlank() }.distinct().takeLast(8)
        saved.remove(old)
        saved.add(0, Saved(p.name, p.detail, p.point, aliases, System.currentTimeMillis()))
        if (saved.size > 500) saved = saved.sortedByDescending { it.used }.take(500).toMutableList()
        save()
    }

    fun all(): List<Saved> = saved

    fun forget(name: String): Boolean {
        val s = findSaved(name) ?: return false
        saved.remove(s); save(); return true
    }

    /**
     * Pesquisa na web (Google, pelo Gemini) quando o mapa não conhece o lugar.
     * Devolve o lugar já com o ponto no mapa, ou null.
     */
    suspend fun webSearch(query: String, near: GeoPoint?): Place? = withContext(Dispatchers.IO) {
        val key = Keys.gemini
        if (key.isBlank()) return@withContext null
        val whereTxt = near?.let { String.format(Locale.US, "O piloto está agora perto de %.5f, %.5f (Brasil).", it.lat, it.lon) } ?: "O piloto está no Brasil."
        val prompt = """
            Encontre o lugar/estabelecimento que o piloto pediu por voz: "$query".
            $whereTxt Prefira o que fica mais perto dele.
            O nome veio de reconhecimento de voz e pode ter erro de escrita (ex.: "Sabenalto" = "Sabenauto").
            Pesquise no Google e responda SOMENTE um JSON, sem texto antes ou depois:
            {"nome":"nome correto do lugar","endereco":"rua, número, bairro, cidade, UF","lat":0.0,"lon":0.0,"achou":true}
            Se não tiver certeza de qual é o lugar, responda {"achou":false}.
        """.trimIndent()
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .toString()
        var text: String? = null
        for (model in listOf("gemini-flash-latest", "gemini-3.5-flash", "gemini-flash-lite-latest", "gemini-2.5-flash")) {
            val r = Http.request(
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                "POST", mapOf("x-goog-api-key" to key), body, readTimeoutMs = 25_000,
            )
            if (!r.ok) {
                Http.report("busca-web", "$model -> ${r.code} ${r.reason()} ${r.text.take(200)}")
                continue
            }
            text = runCatching {
                val parts = JSONObject(r.text).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
            }.getOrNull()
            if (!text.isNullOrBlank()) break
        }
        val raw = text ?: return@withContext null
        val json = runCatching {
            JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
        }.getOrNull() ?: run {
            Http.report("busca-web", "\"$query\": resposta sem JSON: ${raw.take(200)}")
            return@withContext null
        }
        if (!json.optBoolean("achou", false)) {
            Http.report("busca-web", "\"$query\": Google não achou")
            return@withContext null
        }
        val name = json.optString("nome").ifBlank { query }
        val addr = json.optString("endereco")
        val gLat = json.optDouble("lat", Double.NaN)
        val gLon = json.optDouble("lon", Double.NaN)
        val gPoint = if (!gLat.isNaN() && !gLon.isNaN() && gLat != 0.0) GeoPoint(gLat, gLon) else null
        // o ponto confiável vem do endereço convertido pelo mapa; a coordenada do Google só desempata
        val byAddr = if (addr.isNotBlank()) Geocoder.search(addr, gPoint ?: near) else emptyList()
        val pick = byAddr.minByOrNull { c -> (gPoint ?: near)?.distanceTo(c.point) ?: 0.0 }
        val point = when {
            pick != null && (gPoint == null || pick.point.distanceTo(gPoint) < 3_000) -> pick.point
            gPoint != null -> gPoint
            else -> pick?.point
        } ?: return@withContext null
        Http.report("busca-web", "\"$query\" -> $name | $addr | ${String.format(Locale.US, "%.5f,%.5f", point.lat, point.lon)}")
        Place(name, addr.ifBlank { "achado na busca do Google" }, point)
    }

    private fun load(): MutableList<Saved> = try {
        if (!file.exists()) mutableListOf() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val al = o.optJSONArray("apelidos") ?: JSONArray()
                Saved(
                    o.getString("nome"), o.optString("detalhe"), GeoPoint(o.getDouble("lat"), o.getDouble("lon")),
                    (0 until al.length()).map { al.getString(it) }, o.optLong("usado"),
                )
            }.toMutableList()
        }
    } catch (_: Exception) {
        mutableListOf()
    }

    private fun save() {
        val arr = JSONArray()
        saved.forEach { s ->
            arr.put(
                JSONObject().put("nome", s.name).put("detalhe", s.detail).put("lat", s.point.lat).put("lon", s.point.lon)
                    .put("apelidos", JSONArray(s.aliases)).put("usado", s.used),
            )
        }
        runCatching { file.writeText(arr.toString()) }
    }
}

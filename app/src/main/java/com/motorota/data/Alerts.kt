package com.motorota.data

import android.content.Context
import com.motorota.routing.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.floor

// ------------------------------------------------------------------
// Radares fixos (vêm dentro do app, do OpenStreetMap)
// ------------------------------------------------------------------

data class Radar(val point: GeoPoint, val limitKmh: Int, val directionDeg: Int?)

class RadarStore(private val ctx: Context) {
    @Volatile var all: List<Radar> = emptyList(); private set
    @Volatile private var grid = HashMap<Long, MutableList<Radar>>()

    /** Lê o arquivo de radares fora da thread principal. */
    fun load() {
        val nextGrid = HashMap<Long, MutableList<Radar>>()
        val list = ArrayList<Radar>(12000)
        try {
            // versão da semana baixada do servidor; se ainda não tem, a que veio com o app
            val src = if (onlineFile.length() > 100_000) onlineFile.inputStream() else ctx.assets.open("radares.csv")
            src.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith("#") || line.isBlank()) return@forEach
                    val p = line.split(',')
                    if (p.size < 3) return@forEach
                    val lat = p[0].toDoubleOrNull() ?: return@forEach
                    val lon = p[1].toDoubleOrNull() ?: return@forEach
                    val r = Radar(
                        GeoPoint(lat, lon),
                        p[2].toIntOrNull() ?: 0,
                        p.getOrNull(3)?.trim()?.toIntOrNull(),
                    )
                    list.add(r)
                    nextGrid.getOrPut(cell(lat, lon)) { ArrayList() }.add(r)
                }
            }
        } catch (_: Exception) {
        }
        // radares que o piloto e a turma marcaram (ficam para sempre, somados aos do mapa)
        for (r in loadUser() + loadCsv(sharedFile)) {
            if (nextGrid[cell(r.point.lat, r.point.lon)]?.any { it.point.distanceTo(r.point) < 60 } == true) continue
            list.add(r)
            nextGrid.getOrPut(cell(r.point.lat, r.point.lon)) { ArrayList() }.add(r)
        }
        this.grid = nextGrid
        all = list
    }

    private val userFile get() = java.io.File(ctx.filesDir, "radares_usuario.csv")
    private val onlineFile get() = java.io.File(ctx.filesDir, "radares_semana.csv")
    private val sharedFile get() = java.io.File(ctx.filesDir, "radares_turma.csv")

    private fun loadCsv(f: java.io.File): List<Radar> = try {
        if (!f.exists()) emptyList() else f.readLines().mapNotNull { line ->
            val p = line.split(',')
            val lat = p.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = p.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            Radar(GeoPoint(lat, lon), p.getOrNull(2)?.toIntOrNull() ?: 0, null)
        }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Radares atualizados: a lista do OpenStreetMap é refeita toda semana no servidor (GitHub) e
     * os radares que a turma marcou ficam na central. Baixa se a cópia tiver mais de 7 dias.
     * Devolve true se mudou algo (aí é só chamar load() de novo).
     */
    fun refreshOnline(force: Boolean = false): Boolean {
        var changed = false
        val week = 7 * 86_400_000L
        if (force || !onlineFile.exists() || System.currentTimeMillis() - onlineFile.lastModified() > week) {
            val r = Http.request(
                "https://raw.githubusercontent.com/invictusbr1/MotoRota/main/app/src/main/assets/radares.csv",
                readTimeoutMs = 30_000,
            )
            if (r.ok && r.text.length > 100_000 && r.text.lines().size > 5_000) {
                val tmp = java.io.File(ctx.filesDir, "radares_semana.tmp")
                tmp.writeText(r.text)
                tmp.renameTo(onlineFile)
                changed = true
            }
        }
        val s = Http.request(
            "${com.motorota.intercom.Intercom.SUPABASE_URL}/rest/v1/rpc/listar_radares_reportados", "POST",
            mapOf(
                "apikey" to com.motorota.intercom.Intercom.SUPABASE_KEY,
                "Authorization" to "Bearer ${com.motorota.intercom.Intercom.SUPABASE_KEY}",
            ),
            "{}",
        )
        if (s.ok) {
            val arr = runCatching { JSONArray(s.text) }.getOrNull()
            if (arr != null) {
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    sb.append(o.optDouble("lat")).append(',').append(o.optDouble("lon")).append(',').append(o.optInt("limite")).append('\n')
                }
                if (!sharedFile.exists() || sharedFile.readText() != sb.toString()) {
                    sharedFile.writeText(sb.toString())
                    changed = true
                }
            }
        }
        return changed
    }

    /** Quantos radares tem e de quando é a lista. */
    fun info(): String {
        val days = if (onlineFile.exists()) (System.currentTimeMillis() - onlineFile.lastModified()) / 86_400_000L else -1
        return "${all.size} radares" + if (days >= 0) " (lista de $days dia(s) atrás)" else " (lista que veio com o app)"
    }

    private fun loadUser(): List<Radar> = try {
        if (!userFile.exists()) emptyList() else userFile.readLines().mapNotNull { line ->
            val p = line.split(',')
            val lat = p.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = p.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            Radar(GeoPoint(lat, lon), p.getOrNull(2)?.toIntOrNull() ?: 0, null)
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** O piloto marcou um radar que não estava no mapa. Devolve false se já existia um ali perto. */
    @Synchronized
    fun addUser(p: GeoPoint, limitKmh: Int): Boolean {
        if (near(p).any { it.point.distanceTo(p) < 60 }) return false
        userFile.appendText("${p.lat},${p.lon},$limitKmh\n")
        val r = Radar(p, limitKmh, null)
        val g = HashMap(grid)
        g.getOrPut(cell(p.lat, p.lon)) { ArrayList() }.add(r)
        grid = g
        all = all + r
        return true
    }

    /** Radares a até ~1 km do ponto (consulta rápida por grade de 0,01 grau). */
    fun near(p: GeoPoint): List<Radar> {
        val out = ArrayList<Radar>()
        val la = floor(p.lat * 100).toLong()
        val lo = floor(p.lon * 100).toLong()
        for (dy in -1..1) {
            for (dx in -1..1) {
                grid[key(la + dy, lo + dx)]?.let { out.addAll(it) }
            }
        }
        return out
    }

    private fun cell(lat: Double, lon: Double) = key(floor(lat * 100).toLong(), floor(lon * 100).toLong())
    private fun key(la: Long, lo: Long) = (la shl 32) xor (lo and 0xffffffffL)
}

// ------------------------------------------------------------------
// Alertas marcados pelo usuário (estilo Waze)
// ------------------------------------------------------------------

enum class HazardType(val emoji: String, val label: String, val speech: String, val hoursValid: Int) {
    BURACO("🕳️", "Buraco", "buraco na pista", 24 * 60),
    POLICIA("👮", "Polícia", "polícia à frente", 4),
    ACIDENTE("💥", "Acidente", "acidente na pista", 4),
    RADAR_MOVEL("📸", "Radar móvel", "radar móvel", 6),
    OBRA("🚧", "Obra", "obra na pista", 24 * 30),
    PERIGO("⚠️", "Perigo", "perigo na pista", 12),
    OLEO("🛢️", "Óleo / areia", "óleo ou areia na pista", 24 * 2),
    ANIMAL("🐄", "Animal", "animal na pista", 3),
}

data class Hazard(val id: Long, val type: HazardType, val point: GeoPoint, val createdAt: Long) {
    val expiresAt get() = createdAt + type.hoursValid * 3_600_000L
}

class HazardStore(ctx: Context) {
    private val file = File(ctx.filesDir, "alertas.json")

    fun load(): List<Hazard> {
        if (!file.exists()) return emptyList()
        val now = System.currentTimeMillis()
        return try {
            val arr = JSONArray(file.readText())
            val allParsed = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val type = runCatching { HazardType.valueOf(o.getString("tipo")) }.getOrNull() ?: return@mapNotNull null
                Hazard(o.getLong("id"), type, GeoPoint(o.getDouble("lat"), o.getDouble("lon")), o.getLong("criado"))
            }
            val active = allParsed.filter { it.expiresAt > now }
            if (active.size != allParsed.size) save(active)
            active
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(list: List<Hazard>) {
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        list.filter { it.expiresAt > now }.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("tipo", it.type.name)
                    .put("lat", it.point.lat)
                    .put("lon", it.point.lon)
                    .put("criado", it.createdAt)
            )
        }
        atomicWrite(file, arr.toString())
    }
}

// ------------------------------------------------------------------
// Passeios salvos
// ------------------------------------------------------------------

data class SavedPoint(val point: GeoPoint, val name: String)

data class SavedTrip(
    val id: Long,
    val name: String,
    val origin: SavedPoint?,
    val stops: List<SavedPoint>,
    val destination: SavedPoint,
    val mode: String,
)

class TripStore(ctx: Context) {
    private val file = File(ctx.filesDir, "passeios.json")

    fun load(): List<SavedTrip> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    val stops = o.getJSONArray("paradas")
                    SavedTrip(
                        id = o.getLong("id"),
                        name = o.getString("nome"),
                        origin = o.optJSONObject("partida")?.let(::pt),
                        stops = (0 until stops.length()).map { pt(stops.getJSONObject(it)) },
                        destination = pt(o.getJSONObject("destino")),
                        mode = o.optString("modo", "DESEMPENHO"),
                    )
                }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(list: List<SavedTrip>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("nome", t.name)
                    .put("modo", t.mode)
                    .put("partida", t.origin?.let(::js))
                    .put("paradas", JSONArray().apply { t.stops.forEach { put(js(it)) } })
                    .put("destino", js(t.destination))
            )
        }
        atomicWrite(file, arr.toString())
    }

    private fun pt(o: JSONObject) = SavedPoint(GeoPoint(o.getDouble("lat"), o.getDouble("lon")), o.optString("nome"))
    private fun js(p: SavedPoint) = JSONObject().put("lat", p.point.lat).put("lon", p.point.lon).put("nome", p.name)
}

internal fun atomicWrite(file: File, content: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(content)
    if (!tmp.renameTo(file)) {
        file.writeText(content)
        tmp.delete()
    }
}

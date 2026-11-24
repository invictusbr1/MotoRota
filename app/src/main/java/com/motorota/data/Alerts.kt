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
            ctx.assets.open("radares.csv").bufferedReader().useLines { lines ->
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
        this.grid = nextGrid
        all = list
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

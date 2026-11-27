package com.motorota.data

import android.content.Context
import com.motorota.routing.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Uma viagem feita (o que realmente foi rodado), guardada no diário. */
data class RiddenTrip(
    val id: Long,
    val name: String,
    val startedAt: Long,
    val endedAt: Long,
    val from: String,
    val to: String,
    val distM: Double,
    val timeS: Long,
    val movingS: Long,
    val avgKmh: Int,
    val maxKmh: Int,
    val liters: Double,
    val cost: Double,
    val arrived: Boolean,
    val bike: String,
    val note: String,
    val track: List<GeoPoint>,
)

/** Diário de viagens: tudo que foi rodado navegando fica salvo no celular, com o traçado. */
class TripLog(ctx: Context) {
    private val dir = File(ctx.filesDir, "diario").apply { mkdirs() }
    private val index = File(dir, "viagens.json")

    fun load(): List<RiddenTrip> = try {
        if (!index.exists()) emptyList() else {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).mapNotNull { runCatching { parse(arr.getJSONObject(it), withTrack = false) }.getOrNull() }
                .sortedByDescending { it.startedAt }
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** traçado completo (fica num arquivo separado para a lista abrir rápido) */
    fun track(id: Long): List<GeoPoint> = try {
        val f = File(dir, "trilha_$id.json")
        if (!f.exists()) emptyList() else {
            val a = JSONArray(f.readText())
            (0 until a.length() / 2).map { GeoPoint(a.getDouble(it * 2), a.getDouble(it * 2 + 1)) }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun add(t: RiddenTrip, all: List<RiddenTrip>): List<RiddenTrip> {
        val a = JSONArray()
        t.track.forEach { a.put(Math.round(it.lat * 1e5) / 1e5).put(Math.round(it.lon * 1e5) / 1e5) }
        atomicWrite(File(dir, "trilha_${t.id}.json"), a.toString())
        val list = listOf(t.copy(track = emptyList())) + all.filter { it.id != t.id }
        saveIndex(list)
        return list
    }

    fun update(t: RiddenTrip, all: List<RiddenTrip>): List<RiddenTrip> {
        val list = all.map { if (it.id == t.id) t.copy(track = emptyList()) else it }
        saveIndex(list)
        return list
    }

    fun delete(id: Long, all: List<RiddenTrip>): List<RiddenTrip> {
        File(dir, "trilha_$id.json").delete()
        val list = all.filter { it.id != id }
        saveIndex(list)
        return list
    }

    private fun saveIndex(list: List<RiddenTrip>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject().put("id", t.id).put("nome", t.name).put("inicio", t.startedAt).put("fim", t.endedAt)
                    .put("de", t.from).put("para", t.to).put("dist", t.distM).put("tempo", t.timeS).put("mov", t.movingS)
                    .put("media", t.avgKmh).put("max", t.maxKmh).put("litros", t.liters).put("custo", t.cost)
                    .put("chegou", t.arrived).put("moto", t.bike).put("obs", t.note),
            )
        }
        atomicWrite(index, arr.toString())
    }

    private fun parse(o: JSONObject, withTrack: Boolean) = RiddenTrip(
        id = o.getLong("id"), name = o.optString("nome"), startedAt = o.optLong("inicio"), endedAt = o.optLong("fim"),
        from = o.optString("de"), to = o.optString("para"), distM = o.optDouble("dist"), timeS = o.optLong("tempo"),
        movingS = o.optLong("mov"), avgKmh = o.optInt("media"), maxKmh = o.optInt("max"), liters = o.optDouble("litros", 0.0),
        cost = o.optDouble("custo", 0.0), arrived = o.optBoolean("chegou"), bike = o.optString("moto"), note = o.optString("obs"),
        track = if (withTrack) track(o.getLong("id")) else emptyList(),
    )
}

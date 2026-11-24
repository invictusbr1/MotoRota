package com.motorota.data

import android.content.Context
import com.motorota.routing.GeoPoint
import com.motorota.routing.RiskArea
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Áreas de risco marcadas pelo usuário. Ficam salvas no próprio celular. */
class RiskStore(ctx: Context) {
    private val file = File(ctx.filesDir, "areas_de_risco.json")

    fun load(): List<RiskArea> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    RiskArea(
                        id = o.getLong("id"),
                        point = GeoPoint(o.getDouble("lat"), o.getDouble("lon")),
                        radiusM = o.getInt("raio"),
                        label = o.optString("nome", "Área de risco"),
                        createdAt = o.optLong("criado", 0),
                    )
                }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(list: List<RiskArea>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("lat", it.point.lat)
                    .put("lon", it.point.lon)
                    .put("raio", it.radiusM)
                    .put("nome", it.label)
                    .put("criado", it.createdAt)
            )
        }
        atomicWrite(file, arr.toString())
    }
}

package com.motorota.data

import android.content.Context
import com.motorota.routing.GeoPoint
import com.motorota.routing.RouteResult
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil

/**
 * Preço médio da gasolina por estado, do levantamento semanal da ANP.
 * O servidor (GitHub) atualiza toda terça; o app baixa uma vez por semana e guarda (funciona offline).
 */
object FuelPrices {
    private const val URL = "https://raw.githubusercontent.com/invictusbr1/MotoRota/main/dados/combustivel.json"
    @Volatile private var data: JSONObject? = null
    private var file: File? = null

    /** capital de cada estado: o trecho da rota usa o preço do estado da capital mais próxima */
    private val CAPITALS = mapOf(
        "AC" to GeoPoint(-9.97, -67.81), "AL" to GeoPoint(-9.65, -35.73), "AP" to GeoPoint(0.03, -51.05),
        "AM" to GeoPoint(-3.12, -60.02), "BA" to GeoPoint(-12.97, -38.50), "CE" to GeoPoint(-3.73, -38.52),
        "DF" to GeoPoint(-15.79, -47.88), "ES" to GeoPoint(-20.32, -40.34), "GO" to GeoPoint(-16.68, -49.25),
        "MA" to GeoPoint(-2.53, -44.30), "MT" to GeoPoint(-15.60, -56.10), "MS" to GeoPoint(-20.44, -54.65),
        "MG" to GeoPoint(-19.92, -43.94), "PA" to GeoPoint(-1.46, -48.49), "PB" to GeoPoint(-7.12, -34.86),
        "PR" to GeoPoint(-25.43, -49.27), "PE" to GeoPoint(-8.05, -34.88), "PI" to GeoPoint(-5.09, -42.80),
        "RJ" to GeoPoint(-22.91, -43.17), "RN" to GeoPoint(-5.79, -35.21), "RS" to GeoPoint(-30.03, -51.23),
        "RO" to GeoPoint(-8.76, -63.90), "RR" to GeoPoint(2.82, -60.67), "SC" to GeoPoint(-27.60, -48.55),
        "SP" to GeoPoint(-23.55, -46.63), "SE" to GeoPoint(-10.91, -37.07), "TO" to GeoPoint(-10.18, -48.33),
    )

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "combustivel.json")
        file = f
        if (data == null && f.exists()) data = runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    /** Baixa a tabela da semana se a cópia tiver mais de 3 dias. */
    fun refresh() {
        val f = file ?: return
        if (f.exists() && data != null && System.currentTimeMillis() - f.lastModified() < 3 * 86_400_000L) return
        val r = Http.request(URL, readTimeoutMs = 15_000)
        if (!r.ok) return
        val o = runCatching { JSONObject(r.text) }.getOrNull() ?: return
        if ((o.optJSONObject("estados")?.length() ?: 0) < 20) return
        f.writeText(r.text)
        data = o
    }

    val week: String? get() = data?.optString("semana")?.takeIf { it.isNotBlank() }

    fun stateOf(p: GeoPoint): String = CAPITALS.minByOrNull { it.value.distanceTo(p) }!!.key

    /** preço por litro no ponto (tipo: "comum" ou "aditivada"); null se ainda não tem a tabela */
    fun priceAt(p: GeoPoint, type: String): Double? {
        val d = data ?: return null
        val st = d.optJSONObject("estados")?.optJSONObject(stateOf(p))
        val v = st?.optDouble(type, Double.NaN) ?: Double.NaN
        if (!v.isNaN()) return v
        val br = d.optJSONObject("brasil")?.optDouble(type, Double.NaN) ?: Double.NaN
        return br.takeIf { !it.isNaN() }
    }

    data class TripCost(val liters: Double, val reais: Double, val pricePerL: Double, val refuels: Int, val states: List<String>)

    /**
     * Quanto a viagem gasta: litros pelo consumo da moto e preço de cada trecho pelo estado onde ele fica.
     * [manualPrice] > 0 usa esse preço em tudo. [leftKm]/[fullRangeKm] dizem quantas paradas para abastecer.
     */
    fun tripCost(r: RouteResult, kmL: Double, type: String, manualPrice: Double, leftKm: Int?, fullRangeKm: Int?): TripCost? {
        if (kmL <= 0 || r.points.size < 2) return null
        var liters = 0.0; var reais = 0.0
        val states = LinkedHashSet<String>()
        var last = 0
        var nextMark = 10_000.0
        fun add(a: Int, b: Int) {
            val km = (r.cumDist[b] - r.cumDist[a]) / 1000.0
            val mid = r.points[(a + b) / 2]
            val l = km / kmL
            val price = if (manualPrice > 0) manualPrice else priceAt(mid, type) ?: return
            liters += l; reais += l * price
            states.add(stateOf(mid))
        }
        for (i in 1 until r.points.size) {
            if (r.cumDist[i] >= nextMark || i == r.points.size - 1) {
                add(last, i); last = i; nextMark = r.cumDist[i] + 10_000.0
            }
        }
        if (liters <= 0) return null
        val totalKm = r.cumDist.last() / 1000.0
        val refuels = if (leftKm != null && fullRangeKm != null && fullRangeKm > 0) {
            // abastece com ~15% de sobra no tanque (margem de segurança)
            val usable = fullRangeKm * 0.85
            if (totalKm <= leftKm - 20) 0 else ceil((totalKm - (leftKm - 20).coerceAtLeast(0)) / usable).toInt()
        } else 0
        return TripCost(liters, reais, reais / liters, refuels, states.toList())
    }
}

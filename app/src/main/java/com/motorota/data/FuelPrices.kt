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

    /** Baixa a tabela da semana se a cópia tiver mais de 3 dias (do GitHub; se não tiver, direto da ANP). */
    fun refresh() {
        val f = file ?: return
        if (f.exists() && data != null && System.currentTimeMillis() - f.lastModified() < 3 * 86_400_000L) return
        val r = Http.request(URL, readTimeoutMs = 15_000)
        val o = if (r.ok) runCatching { JSONObject(r.text) }.getOrNull() else null
        if (o != null && (o.optJSONObject("estados")?.length() ?: 0) >= 20) {
            f.writeText(r.text)
            data = o
            return
        }
        // o servidor não conseguiu (o site da ANP às vezes bloqueia servidores): o celular baixa direto
        val anp = runCatching { fromAnp() }.onFailure { Http.report("combustivel", "ANP direto: $it") }.getOrNull() ?: return
        f.writeText(anp.toString())
        data = anp
    }

    private const val ANP_PAGE =
        "https://www.gov.br/anp/pt-br/assuntos/precos-e-defesa-da-concorrencia/precos/levantamento-de-precos-de-combustiveis-ultimas-semanas-pesquisadas"
    private val UF = mapOf(
        "ACRE" to "AC", "ALAGOAS" to "AL", "AMAPA" to "AP", "AMAZONAS" to "AM", "BAHIA" to "BA", "CEARA" to "CE",
        "DISTRITO FEDERAL" to "DF", "ESPIRITO SANTO" to "ES", "GOIAS" to "GO", "MARANHAO" to "MA", "MATO GROSSO" to "MT",
        "MATO GROSSO DO SUL" to "MS", "MINAS GERAIS" to "MG", "PARA" to "PA", "PARAIBA" to "PB", "PARANA" to "PR",
        "PERNAMBUCO" to "PE", "PIAUI" to "PI", "RIO DE JANEIRO" to "RJ", "RIO GRANDE DO NORTE" to "RN",
        "RIO GRANDE DO SUL" to "RS", "RONDONIA" to "RO", "RORAIMA" to "RR", "SANTA CATARINA" to "SC", "SAO PAULO" to "SP",
        "SERGIPE" to "SE", "TOCANTINS" to "TO",
    )

    /** Lê a planilha semanal da ANP (aba ESTADOS) direto no celular. */
    private fun fromAnp(): JSONObject? {
        val page = Http.request(ANP_PAGE, readTimeoutMs = 20_000)
        if (!page.ok) return null
        val link = Regex("https://[^\"]*resumo_semanal_lpc[^\"]*\\.xlsx").find(page.text)?.value ?: return null
        val conn = (java.net.URL(link).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        val files = HashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet")) files[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        val shared = ArrayList<String>()
        files["xl/sharedStrings.xml"]?.let { xml ->
            val p = android.util.Xml.newPullParser(); p.setInput(xml.inputStream(), "UTF-8")
            val sb = StringBuilder(); var inSi = false; var inT = false
            while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (p.name) { "si" -> { inSi = true; sb.setLength(0) }; "t" -> inT = true }
                    org.xmlpull.v1.XmlPullParser.TEXT -> if (inSi && inT) sb.append(p.text)
                    org.xmlpull.v1.XmlPullParser.END_TAG -> when (p.name) { "t" -> inT = false; "si" -> { inSi = false; shared.add(sb.toString()) } }
                }
            }
        }
        val estados = JSONObject(); var semana = ""
        val tot = HashMap<String, DoubleArray>()
        for ((name, xml) in files) {
            if (!name.startsWith("xl/worksheets/")) continue
            val rows = ArrayList<List<String>>()
            val p = android.util.Xml.newPullParser(); p.setInput(xml.inputStream(), "UTF-8")
            var row = ArrayList<String>(); var type = ""; var inV = false; val v = StringBuilder(); var col = 0
            while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (p.name) {
                        "row" -> row = ArrayList()
                        "c" -> {
                            type = p.getAttributeValue(null, "t") ?: ""; v.setLength(0)
                            // coluna pela referência ("D12" -> 3): células vazias não vêm no arquivo
                            col = p.getAttributeValue(null, "r")?.takeWhile { it.isLetter() }
                                ?.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) }?.minus(1) ?: row.size
                        }
                        "v", "t" -> inV = true
                    }
                    org.xmlpull.v1.XmlPullParser.TEXT -> if (inV) v.append(p.text)
                    org.xmlpull.v1.XmlPullParser.END_TAG -> when (p.name) {
                        "v", "t" -> inV = false
                        "c" -> {
                            while (row.size < col) row.add("")
                            row.add(if (type == "s") shared.getOrElse(v.toString().toIntOrNull() ?: -1) { "" } else v.toString())
                        }
                        "row" -> rows.add(row)
                    }
                }
            }
            val hi = rows.indexOfFirst { it.contains("ESTADOS") && it.contains("PRODUTO") }
            if (hi < 0) continue
            val h = rows[hi]
            val iUf = h.indexOf("ESTADOS"); val iProd = h.indexOf("PRODUTO")
            val iPreco = h.indexOf("PREÇO MÉDIO REVENDA"); val iN = h.indexOf("NÚMERO DE POSTOS PESQUISADOS")
            for (r in rows.drop(hi + 1)) {
                val uf = UF[r.getOrNull(iUf)?.trim()] ?: continue
                val prod = when (r.getOrNull(iProd)?.trim()) {
                    "GASOLINA COMUM" -> "comum"; "GASOLINA ADITIVADA" -> "aditivada"; "ETANOL HIDRATADO" -> "etanol"; else -> null
                } ?: continue
                val preco = r.getOrNull(iPreco)?.toDoubleOrNull() ?: continue
                val n = r.getOrNull(iN)?.toDoubleOrNull() ?: 0.0
                (estados.optJSONObject(uf) ?: JSONObject().also { estados.put(uf, it) }).put(prod, Math.round(preco * 100) / 100.0)
                tot.getOrPut(prod) { DoubleArray(2) }.let { it[0] += preco * n; it[1] += n }
            }
            Regex("lpc_(\\d{4})[_-](\\d{2})[_-](\\d{2})[_-](\\d{4})[_-](\\d{2})[_-](\\d{2})").find(link)?.groupValues?.let {
                semana = "${it[3]}/${it[2]} a ${it[6]}/${it[5]}/${it[4]}"
            }
        }
        if (estados.length() < 20) return null
        val br = JSONObject()
        tot.forEach { (k, a) -> if (a[1] > 0) br.put(k, Math.round(a[0] / a[1] * 100) / 100.0) }
        return JSONObject().put("fonte", "ANP").put("semana", semana).put("estados", estados).put("brasil", br)
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

package com.motorota.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.io.File
import kotlin.math.pow

/** Modelo de moto com os dados oficiais do fabricante. */
data class BikeModel(
    val id: String,
    val brand: String,
    val name: String,
    val style: String,
    val tankL: Double,
    val reserveL: Double,
    /** consumo oficial do fabricante (km por litro) */
    val officialKmL: Double,
    val engine: String,
    /** cor do cartão (ARGB) */
    val color: Long,
    val highlights: List<String>,
) {
    val fullName get() = "$brand $name"
}

object Bikes {
    val CATALOG = listOf(
        BikeModel(
            id = "tiger900rp", brand = "Triumph", name = "Tiger 900 Rally Pro", style = "Big trail",
            tankL = 20.0, reserveL = 3.5, officialKmL = 100 / 4.7, engine = "888 cc · 3 cilindros",
            color = 0xFF3B4A2F,
            highlights = listOf("Tanque de 20 L", "Consumo oficial 4,7 L/100 km", "6 modos de pilotagem", "Revisão a cada 10.000 km"),
        ),
        BikeModel(
            id = "r1300gs", brand = "BMW", name = "R 1300 GS", style = "Big trail",
            tankL = 19.0, reserveL = 4.0, officialKmL = 100 / 4.8, engine = "1.300 cc · boxer 2 cilindros",
            color = 0xFF1E3A8A,
            highlights = listOf("Tanque útil de 19 L (reserva ~4 L)", "Consumo oficial 4,8 L/100 km (WMTC)", "145 cv"),
        ),
        BikeModel(
            id = "supermeteor650", brand = "Royal Enfield", name = "Super Meteor 650", style = "Custom / cruiser",
            tankL = 15.7, reserveL = 3.1, officialKmL = 23.7, engine = "648 cc · bicilíndrico",
            color = 0xFF14532D,
            highlights = listOf("Tanque de 15,7 L (reserva 3,1 L)", "Consumo oficial 23,7 km/L", "Banco baixo: 740 mm"),
        ),
    )

    fun byId(id: String?) = CATALOG.firstOrNull { it.id == id }

    /** O número oficial é de laboratório; na rua a moto gasta um pouco mais. */
    const val REAL_WORLD = 0.9

    /**
     * Quanto rende em relação ao normal conforme a velocidade:
     * anda-e-para gasta mais, estrada a 60-90 é o ideal, acima disso o vento cobra a conta.
     */
    fun speedFactor(kmh: Double): Double = when {
        kmh < 5 -> 1.0
        kmh < 25 -> 0.85
        kmh <= 90 -> 1.0
        else -> (90.0 / kmh).pow(0.9)
    }
}

/** A moto do piloto, com o que o app aprendeu dela. Fica salva só no celular. */
data class BikeProfile(
    val modelId: String,
    /** para "outra moto" */
    val customName: String = "",
    val customTankL: Double = 0.0,
    val customKmL: Double = 0.0,
    val photo: String? = null,
    /** ajuste aprendido nos abastecimentos (1.0 = igual ao estimado) */
    val learned: Double = 1.0,
    val learnedFills: Int = 0,
    /** ajuste automático pela velocidade e pelos abastecimentos */
    val autoAdjust: Boolean = true,
    /** litros gastos desde o último "Abasteci" (estimado) */
    val usedL: Double = 0.0,
) {
    val model get() = Bikes.byId(modelId)
    val name get() = model?.fullName ?: customName.ifBlank { "Minha moto" }
    val tankL get() = model?.tankL ?: customTankL
    val reserveL get() = model?.reserveL ?: (tankL * 0.18)

    /** km por litro que o app está usando agora */
    val kmL: Double
        get() {
            val base = model?.let { it.officialKmL * Bikes.REAL_WORLD } ?: customKmL
            return if (autoAdjust) base * learned else base
        }

    /** margem de erro da estimativa: diminui conforme o app aprende com os abastecimentos */
    val marginPct get() = when {
        !autoAdjust -> 12
        learnedFills >= 3 -> 5
        learnedFills >= 1 -> 8
        else -> 12
    }

    val fullRangeKm get() = (tankL * kmL).toInt()
    val leftL get() = (tankL - usedL).coerceAtLeast(0.0)
    val leftKm get() = (leftL * kmL).toInt()

    fun toJson(): String = JSONObject()
        .put("modelo", modelId).put("nome", customName).put("tanque", customTankL).put("kml", customKmL)
        .put("foto", photo ?: "").put("aprendido", learned).put("abastecimentos", learnedFills)
        .put("auto", autoAdjust).put("gasto", usedL).toString()

    companion object {
        fun fromJson(s: String?): BikeProfile? = runCatching {
            val o = JSONObject(s ?: return null)
            BikeProfile(
                modelId = o.getString("modelo"),
                customName = o.optString("nome"),
                customTankL = o.optDouble("tanque", 0.0),
                customKmL = o.optDouble("kml", 0.0),
                photo = o.optString("foto").takeIf { it.isNotBlank() },
                learned = o.optDouble("aprendido", 1.0),
                learnedFills = o.optInt("abastecimentos", 0),
                autoAdjust = o.optBoolean("auto", true),
                usedL = o.optDouble("gasto", 0.0),
            )
        }.getOrNull()
    }
}

object BikePhoto {
    /** Copia a foto escolhida para dentro do app, reduzida (até 1280 px). Devolve o caminho. */
    fun save(ctx: Context, uri: Uri): String? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 1280) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val f = File(ctx.filesDir, "moto_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        // apaga fotos antigas
        ctx.filesDir.listFiles { x -> x.name.startsWith("moto_") && x.name != f.name }?.forEach { it.delete() }
        f.absolutePath
    }.getOrNull()

    fun load(path: String?): Bitmap? = path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
}

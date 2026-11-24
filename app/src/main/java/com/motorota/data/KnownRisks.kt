package com.motorota.data

import android.content.Context
import com.motorota.routing.GeoPoint

/**
 * Área de risco que já vem com o app (pesquisa em fontes públicas).
 * EVITAR = a rota desvia das ruas internas (as vias expressas da borda continuam liberadas).
 * ATENÇÃO = a rota não muda, só avisa (ex.: estradas campeãs de roubo de moto).
 */
data class KnownRisk(
    val avoid: Boolean,
    val point: GeoPoint,
    val radiusM: Int,
    val name: String,
    val city: String,
    val reason: String,
)

object KnownRisks {
    @Volatile var all: List<KnownRisk> = emptyList(); private set

    /** assets/areas_risco.csv: nivel;lat;lon;raio;nome;cidade;motivo (nivel E = evitar, A = atencao) */
    fun load(ctx: Context) {
        val out = ArrayList<KnownRisk>()
        ctx.assets.open("areas_risco.csv").bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("nivel")) continue
                val c = line.split(';')
                if (c.size < 7) continue
                val lat = c[1].trim().toDoubleOrNull() ?: continue
                val lon = c[2].trim().toDoubleOrNull() ?: continue
                val radius = c[3].trim().toIntOrNull() ?: continue
                out.add(
                    KnownRisk(
                        avoid = c[0].trim() == "E",
                        point = GeoPoint(lat, lon),
                        radiusM = radius,
                        name = c[4].trim(),
                        city = c[5].trim(),
                        reason = c[6].trim(),
                    )
                )
            }
        }
        all = out
    }
}

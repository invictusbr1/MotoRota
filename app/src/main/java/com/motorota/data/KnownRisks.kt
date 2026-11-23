package com.motorota.data

import android.content.Context
import com.motorota.routing.GeoPoint

/**
 * Area de risco que ja vem com o app (pesquisa em fontes publicas).
 * EVITAR = a rota desvia das ruas internas (as vias expressas da borda continuam liberadas).
 * ATENCAO = a rota nao muda, so avisa (ex.: estradas campeas de roubo de moto).
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

    /** assets/areas_risco.csv: nivel;lat;lon;raio;nome;cidade;motivo  (nivel E = evitar, A = atencao) */
    fun load(ctx: Context) {
        val out = ArrayList<KnownRisk>()
        ctx.assets.open("areas_risco.csv").bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("nivel")) continue
                val c = line.split(';')
                if (c.size < 7) continue
                out.add(
                    KnownRisk(
                        avoid = c[0].trim() == "E",
                        point = GeoPoint(c[1].toDouble(), c[2].toDouble()),
                        radiusM = c[3].toInt(),
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

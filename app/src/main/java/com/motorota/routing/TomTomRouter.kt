package com.motorota.routing

import com.motorota.data.Http
import com.motorota.data.KnownRisk
import com.motorota.data.Keys
import com.motorota.data.UrbanZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.cos

/**
 * Rotas pelo mapa da TomTom (online). O mapa deles é atualizado toda semana com dados de
 * frota/GPS, então pega obra nova, rotatória nova e mão invertida bem antes do OpenStreetMap.
 * Sem internet, o app volta para o BRouter (mapa offline).
 */
object TomTomRouter {
    private const val MAX_AREAS = 10 // limite da TomTom

    val available get() = Keys.tomtom.isNotBlank()

    suspend fun route(
        points: List<GeoPoint>,
        mode: Mode,
        risks: List<RiskArea>,
        known: List<KnownRisk>,
        zones: List<UrbanZone>,
    ): RouteResult = withContext(Dispatchers.IO) {
        val circles = ArrayList<Triple<GeoPoint, Double, Int>>() // centro, raio, prioridade
        risks.forEach { circles.add(Triple(it.point, it.radiusM.toDouble(), 0)) }
        if (mode != Mode.RAPIDA) known.filter { it.avoid }.forEach { circles.add(Triple(it.point, it.radiusM.toDouble(), 1)) }
        if (mode == Mode.SEGURA) zones.filter { it.dense }.forEach { circles.add(Triple(it.point, it.radiusM, 2)) }
        val areas = pickAreas(points, circles)

        val avoid = when (mode) {
            Mode.RAPIDA -> emptyList()
            else -> listOf("unpavedRoads")
        }
        var travel = "motorcycle"
        var sections = listOf("speedLimit", "motorway", "unpaved")
        var res = call(points, travel, avoid, sections, areas)
        if (res.code == 400) {
            // modo moto é "beta" na TomTom: se recusar, tenta como carro e sem seções extras
            travel = "car"; sections = emptyList()
            res = call(points, travel, avoid, sections, areas)
        }
        if (!res.ok) {
            Http.report("rota-tomtom", "HTTP ${res.code} ${res.reason()} ${res.text.take(300)}")
            throw RouteException("TomTom: ${res.reason()}")
        }
        parse(mode, res.text)
    }

    /**
     * Segurança primeiro: o caminho vem do motor de segurança (BRouter, que desvia de favelas,
     * zonas urbanas densas e trechos de risco). Aqui a TomTom só "redesenha" ESSE MESMO caminho no
     * mapa novo dela (pega rotatória nova, obra, mão invertida). Se o desenho dela sair do caminho
     * seguro, ou passar mais por área de risco, é descartado e fica o do BRouter.
     */
    suspend fun refine(
        points: List<GeoPoint>,
        base: RouteResult,
        known: List<KnownRisk>,
        zones: List<UrbanZone>,
    ): RouteResult? = withContext(Dispatchers.IO) {
        if (base.points.size < 2) return@withContext null
        val total = base.cumDist.last()
        // viagem longa: a TomTom não segue bem o caminho (desvia dezenas de km); o mapa novo importa mais na cidade
        if (total > 200_000) return@withContext null
        val step = maxOf(150.0, total / 350)
        val sup = ArrayList<GeoPoint>()
        var next = 0.0
        for (i in base.points.indices) {
            if (base.cumDist[i] >= next) { sup.add(base.points[i]); next = base.cumDist[i] + step }
        }
        if (sup.last() != base.points.last()) sup.add(base.points.last())
        val body = JSONObject().put(
            "supportingPoints",
            JSONArray().apply { sup.forEach { put(JSONObject().put("latitude", it.lat).put("longitude", it.lon)) } },
        ).toString()
        val locs = points.joinToString(":") { String.format(Locale.US, "%.6f,%.6f", it.lat, it.lon) }
        fun req(travel: String, sections: Boolean) = Http.request(
            "https://api.tomtom.com/routing/1/calculateRoute/$locs/json?key=${Keys.tomtom}" +
                "&travelMode=$travel&routeType=fastest&traffic=true&instructionsType=coded&language=pt-BR" +
                "&routeRepresentation=polyline&computeTravelTimeFor=none" +
                (if (sections) "&sectionType=speedLimit&sectionType=motorway&sectionType=unpaved" else ""),
            "POST", body = body, readTimeoutMs = 12_000,
        )
        var res = req("motorcycle", true)
        if (res.code == 400) res = req("car", false)
        if (!res.ok) {
            Http.report("rota-tomtom", "refino HTTP ${res.code} ${res.reason()} ${res.text.take(300)}")
            return@withContext null
        }
        val r = parse(base.mode, res.text)
        val why = rejectReason(base, r, known, zones)
        if (why != null) {
            Http.report("rota-tomtom", "refino descartado (${base.mode.label}): $why")
            return@withContext null
        }
        // mantém as estatísticas de piso do motor seguro (paralelepípedo etc.) quando a TomTom não informa
        r.copy(badPavingM = base.badPavingM, unpavedM = maxOf(r.unpavedM, base.unpavedM), note = base.note)
    }

    /**
     * Conserta "volta esquisita": quando o mapa offline está desatualizado (ex.: pista nova da Serra
     * das Araras, viaduto novo, obra), o BRouter acha que a rodovia está interrompida e manda sair
     * por estrada vicinal e voltar mais à frente. Aqui o app procura esses laços (anda muito para
     * chegar perto de onde já estava), pergunta à TomTom o caminho direto só naquele trecho e troca,
     * se for bem mais curto e não passar mais por área de risco.
     */
    suspend fun fixDetours(
        base: RouteResult,
        risks: List<RiskArea>,
        known: List<KnownRisk>,
        zones: List<UrbanZone>,
    ): RouteResult = withContext(Dispatchers.IO) {
        val n = base.points.size
        if (n < 10) return@withContext base
        // pontos a cada ~200 m
        val s = ArrayList<Int>()
        var next = 0.0
        for (i in 0 until n) if (base.cumDist[i] >= next) { s.add(i); next = base.cumDist[i] + 200 }
        if (s.last() != n - 1) s.add(n - 1)
        val found = ArrayList<Pair<Int, Int>>()
        var k = 0
        while (k < s.size && found.size < 4) {
            val a = s[k]
            var best = -1; var bestWaste = 0.0
            var m = k + 1
            while (m < s.size && base.cumDist[s[m]] - base.cumDist[a] <= 40_000) {
                val b = s[m]
                val along = base.cumDist[b] - base.cumDist[a]
                val straight = base.points[a].distanceTo(base.points[b])
                val waste = along - 1.6 * straight
                if (straight < 6_000 && along > 2.5 * straight + 3_000 && waste > bestWaste) { best = m; bestWaste = waste }
                m++
            }
            if (best < 0) { k++; continue }
            found.add(a to s[best])
            k = best + 1
        }
        if (found.isEmpty()) return@withContext base
        var out = base
        var shift = 0 // índices mudam depois de cada troca
        for ((a0, b0) in found) {
            val a = a0 + shift; val b = b0 + shift
            val pa = out.points[a]; val pb = out.points[b]
            val url = "https://api.tomtom.com/routing/1/calculateRoute/" +
                String.format(Locale.US, "%.6f,%.6f:%.6f,%.6f", pa.lat, pa.lon, pb.lat, pb.lon) +
                "/json?key=${Keys.tomtom}&travelMode=car&routeType=fastest&traffic=false&instructionsType=coded" +
                "&language=pt-BR&routeRepresentation=polyline&computeTravelTimeFor=none&sectionType=speedLimit"
            val res = Http.request(url, readTimeoutMs = 10_000)
            if (!res.ok) continue
            val seg = runCatching { parse(out.mode, res.text) }.getOrNull() ?: continue
            val oldLen = out.cumDist[b] - out.cumDist[a]
            val newLen = seg.cumDist.last()
            val sub = slice(out, a, b)
            val worse = exposure(seg, known, zones) > exposure(sub, known, zones) + 200 ||
                seg.points.any { p -> risks.any { p.distanceTo(it.point) < it.radiusM } }
            if (newLen > oldLen * 0.75 || worse) {
                Http.report("rota-desvio", "mantido laço de ${(oldLen / 1000).toInt()} km (TomTom ${(newLen / 1000).toInt()} km, risco=$worse)")
                continue
            }
            Http.report(
                "rota-desvio",
                String.format(Locale.US, "corrigido laço em %.5f,%.5f: %.1f km -> %.1f km", pa.lat, pa.lon, oldLen / 1000, newLen / 1000),
            )
            val before = out.points.size
            out = splice(out, a, b, seg)
            shift += out.points.size - before
        }
        out
    }

    private fun slice(r: RouteResult, a: Int, b: Int): RouteResult {
        val pts = r.points.subList(a, b + 1)
        val cum = DoubleArray(pts.size) { r.cumDist[a + it] - r.cumDist[a] }
        return r.copy(points = pts, cumDist = cum, cumTime = FloatArray(pts.size), hints = emptyList(), speedLimits = emptyList())
    }

    /** Troca os pontos a..b da rota pelo trecho novo, refazendo distâncias, tempos, manobras e limites. */
    private fun splice(r: RouteResult, a: Int, b: Int, seg: RouteResult): RouteResult {
        val pts = ArrayList<GeoPoint>(r.points.size)
        pts.addAll(r.points.subList(0, a + 1))
        pts.addAll(seg.points.drop(1))
        val joinB = pts.size - 1
        pts.addAll(r.points.subList(b + 1, r.points.size))
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + pts[i - 1].distanceTo(pts[i])
        val tA = r.cumTime[a]
        val tSeg = seg.cumTime.last()
        val tOld = r.cumTime[b] - r.cumTime[a]
        val time = FloatArray(pts.size)
        for (i in pts.indices) {
            time[i] = when {
                i <= a -> r.cumTime[i]
                i <= joinB -> tA + seg.cumTime[(i - a).coerceAtMost(seg.cumTime.size - 1)]
                else -> r.cumTime[i - joinB + b] - tOld + tSeg
            }
        }
        val idxShift = joinB - b
        val hints = r.hints.filter { it.index <= a } +
            seg.hints.filter { it.index > 0 }.map { it.copy(index = it.index + a) } +
            r.hints.filter { it.index > b }.map { it.copy(index = it.index + idxShift) }
        val dA = r.cumDist[a]
        val dDelta = cum[joinB] - r.cumDist[b]
        val limits = r.speedLimits.filter { it.endM <= dA } +
            seg.speedLimits.map { SpeedSegment(it.startM + dA, it.endM + dA, it.kmh) } +
            r.speedLimits.filter { it.startM >= r.cumDist[b] }.map { SpeedSegment(it.startM + dDelta, it.endM + dDelta, it.kmh) }
        return r.copy(
            points = pts, cumDist = cum, cumTime = time,
            distanceM = cum.last().toInt(), timeS = time.last().toInt(),
            hints = hints, speedLimits = limits.sortedBy { it.startM },
        )
    }

    /** null = o desenho novo segue o caminho seguro; senão, o motivo de descartar. */
    private fun rejectReason(base: RouteResult, r: RouteResult, known: List<KnownRisk>, zones: List<UrbanZone>): String? {
        val lb = base.cumDist.last(); val lr = r.cumDist.last()
        if (lr > lb * 1.12 + 500 || lr < lb * 0.85) return "distância ${lr.toInt()} m x ${lb.toInt()} m"
        // amostra o novo traçado a cada ~150 m e mede a distância até o caminho seguro
        var far = 0; var n = 0; var worst = 0.0
        var hint = 0
        var next = 0.0
        for (i in r.points.indices) {
            if (r.cumDist[i] < next) continue
            next = r.cumDist[i] + 150
            val (d, at) = nearest(base.points, r.points[i], hint)
            hint = at
            n++
            if (d > 300) far++
            if (d > worst) worst = d
        }
        if (worst > 1200) return "saiu ${worst.toInt()} m do caminho seguro"
        if (n > 0 && far * 100 / n > 3) return "$far de $n pontos fora do caminho seguro"
        val exp0 = exposure(base, known, zones); val exp1 = exposure(r, known, zones)
        if (exp1 > exp0 + 300) return "mais ${(exp1 - exp0).toInt()} m dentro de área de risco"
        return null
    }

    /** distância até o ponto mais próximo do caminho, procurando perto do último achado (é tudo na mesma ordem) */
    private fun nearest(path: List<GeoPoint>, p: GeoPoint, hint: Int): Pair<Double, Int> {
        var best = Double.MAX_VALUE; var at = hint
        val from = (hint - 400).coerceAtLeast(0); val to = (hint + 1500).coerceAtMost(path.size - 1)
        fun scan(a: Int, b: Int) {
            for (j in a until b) {
                val d = segDist(p, path[j], path[j + 1])
                if (d < best) { best = d; at = j }
            }
        }
        scan(from, to)
        if (best > 300) scan(0, path.size - 1) // perdeu a referência: procura no caminho todo
        return best to at
    }

    private fun segDist(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val kx = 111_320 * cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * 110_540
        val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * 110_540
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 > 0) (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0) else 0.0
        val x = ax + t * dx; val y = ay + t * dy
        return kotlin.math.sqrt(x * x + y * y)
    }

    /** metros rodados dentro de áreas críticas conhecidas e zonas urbanas densas */
    fun exposure(r: RouteResult, known: List<KnownRisk>, zones: List<UrbanZone>): Double {
        val circles = known.filter { it.avoid }.map { it.point to it.radiusM.toDouble() } +
            zones.filter { it.dense }.map { it.point to it.radiusM * 0.6 }
        if (circles.isEmpty()) return 0.0
        var m = 0.0
        for (i in 1 until r.points.size) {
            val p = r.points[i]
            if (circles.any { (c, rad) -> p.distanceTo(c) < rad }) m += r.cumDist[i] - r.cumDist[i - 1]
        }
        return m
    }

    private fun call(
        points: List<GeoPoint>,
        travel: String,
        avoid: List<String>,
        sections: List<String>,
        areas: List<Pair<GeoPoint, GeoPoint>>,
    ): com.motorota.data.HttpResult {
        val locs = points.joinToString(":") { String.format(Locale.US, "%.6f,%.6f", it.lat, it.lon) }
        val q = StringBuilder("https://api.tomtom.com/routing/1/calculateRoute/$locs/json?key=${Keys.tomtom}")
        q.append("&travelMode=$travel&routeType=fastest&traffic=true&instructionsType=coded&language=pt-BR")
        q.append("&routeRepresentation=polyline&computeTravelTimeFor=none")
        avoid.forEach { q.append("&avoid=$it") }
        sections.forEach { q.append("&sectionType=$it") }
        val body = if (areas.isEmpty()) null else JSONObject().put(
            "avoidAreas",
            JSONObject().put(
                "rectangles",
                JSONArray().apply {
                    areas.forEach { (sw, ne) ->
                        put(
                            JSONObject()
                                .put("southWestCorner", JSONObject().put("latitude", sw.lat).put("longitude", sw.lon))
                                .put("northEastCorner", JSONObject().put("latitude", ne.lat).put("longitude", ne.lon)),
                        )
                    }
                },
            ),
        ).toString()
        return Http.request(q.toString(), if (body != null) "POST" else "GET", body = body, readTimeoutMs = 20_000)
    }

    /** Até 10 retângulos, os mais próximos do caminho; nunca um que cubra a partida, paradas ou destino. */
    private fun pickAreas(points: List<GeoPoint>, circles: List<Triple<GeoPoint, Double, Int>>): List<Pair<GeoPoint, GeoPoint>> {
        if (circles.isEmpty()) return emptyList()
        fun distToPath(p: GeoPoint): Double {
            var best = Double.MAX_VALUE
            for (i in 0 until points.size - 1) {
                val a = points[i]; val b = points[i + 1]
                // aproximação plana, suficiente para ordenar
                val kx = 111_320 * cos(Math.toRadians(p.lat))
                val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * 110_540
                val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * 110_540
                val dx = bx - ax; val dy = by - ay
                val l2 = dx * dx + dy * dy
                val t = if (l2 > 0) (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                val x = ax + t * dx; val y = ay + t * dy
                best = minOf(best, kotlin.math.sqrt(x * x + y * y))
            }
            return best
        }
        val pathLen = (0 until points.size - 1).sumOf { points[it].distanceTo(points[it + 1]) }
        val maxOff = (pathLen * 0.25).coerceIn(3_000.0, 40_000.0)
        return circles
            .filter { (c, r, _) -> points.none { it.distanceTo(c) < r + 150 } }
            .map { it to distToPath(it.first) - it.second }
            .filter { it.second < maxOff }
            .sortedWith(compareBy({ it.first.third }, { it.second }))
            .take(MAX_AREAS)
            .map { (c, _) ->
                val (p, r) = c.first to c.second
                val dLat = r / 110_540
                val dLon = r / (111_320 * cos(Math.toRadians(p.lat)))
                GeoPoint(p.lat - dLat, p.lon - dLon) to GeoPoint(p.lat + dLat, p.lon + dLon)
            }
    }

    private fun parse(mode: Mode, json: String): RouteResult {
        val route = JSONObject(json).getJSONArray("routes").getJSONObject(0)
        val summary = route.getJSONObject("summary")
        val pts = ArrayList<GeoPoint>()
        val legs = route.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val lp = legs.getJSONObject(l).getJSONArray("points")
            for (i in 0 until lp.length()) {
                val o = lp.getJSONObject(i)
                pts.add(GeoPoint(o.getDouble("latitude"), o.getDouble("longitude")))
            }
        }
        if (pts.size < 2) throw RouteException("A partida e o destino são o mesmo lugar.")
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + pts[i - 1].distanceTo(pts[i])
        val total = cum.last().coerceAtLeast(1.0)
        val timeS = summary.optInt("travelTimeInSeconds")
        val times = FloatArray(pts.size) { (cum[it] / total * timeS).toFloat() }

        var motorway = 0.0
        var unpaved = 0.0
        val limits = ArrayList<SpeedSegment>()
        route.optJSONArray("sections")?.let { s ->
            for (i in 0 until s.length()) {
                val sec = s.getJSONObject(i)
                val a = sec.optInt("startPointIndex").coerceIn(0, pts.size - 1)
                val b = sec.optInt("endPointIndex").coerceIn(0, pts.size - 1)
                val len = cum[b] - cum[a]
                when (sec.optString("sectionType")) {
                    "MOTORWAY" -> motorway += len
                    "UNPAVED" -> unpaved += len
                    "SPEED_LIMIT" -> {
                        val v = sec.optInt("maxSpeedLimitInKmh", 0)
                        if (v > 0) limits.add(SpeedSegment(cum[a], cum[b], v))
                    }
                }
            }
        }

        val hints = ArrayList<RouteHint>()
        route.optJSONObject("guidance")?.optJSONArray("instructions")?.let { ins ->
            for (i in 0 until ins.length()) {
                val o = ins.getJSONObject(i)
                val cmd = cmdFor(o.optString("maneuver")) ?: continue
                val idx = o.optInt("pointIndex").coerceIn(0, pts.size - 1)
                hints.add(RouteHint(idx, cmd, if (cmd == 13) o.optInt("roundaboutExitNumber", 0) else 0))
            }
        }

        return RouteResult(
            mode = mode,
            points = pts,
            cumDist = cum,
            cumTime = times,
            distanceM = summary.optInt("lengthInMeters", total.toInt()),
            timeS = timeS,
            highwayPct = ((motorway * 100) / total).toInt(),
            unpavedM = unpaved.toInt(),
            badPavingM = 0,
            hints = hints,
            speedLimits = limits.sortedBy { it.startM },
            source = "TomTom",
        )
    }

    /** Converte a manobra da TomTom no código usado pelas instruções de voz (padrão BRouter). */
    private fun cmdFor(m: String): Int? = when (m) {
        "STRAIGHT", "KEEP_STRAIGHT", "FOLLOW", "SWITCH_MAIN_ROAD", "SWITCH_PARALLEL_ROAD" -> 1
        "TURN_LEFT" -> 2
        "BEAR_LEFT" -> 3
        "SHARP_LEFT" -> 4
        "TURN_RIGHT" -> 5
        "BEAR_RIGHT" -> 6
        "SHARP_RIGHT" -> 7
        "KEEP_LEFT", "ENTRANCE_RAMP" -> 8
        "KEEP_RIGHT" -> 9
        "MAKE_UTURN", "TRY_MAKE_UTURN" -> 10
        "ROUNDABOUT_CROSS", "ROUNDABOUT_RIGHT", "ROUNDABOUT_LEFT", "ROUNDABOUT_BACK" -> 13
        "MOTORWAY_EXIT_LEFT" -> 17
        "MOTORWAY_EXIT_RIGHT" -> 18
        else -> null // DEPART, ARRIVE, WAYPOINT..., saídas da rotatória etc.
    }
}

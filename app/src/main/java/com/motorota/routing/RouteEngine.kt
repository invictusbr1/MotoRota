package com.motorota.routing

import android.app.ActivityManager
import android.content.Context
import btools.router.FormatJson
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class RouteException(message: String) : Exception(message)

/**
 * Calcula rotas 100% no celular, offline, com o motor BRouter
 * e o perfil moto.brf (que fica em assets/brouter).
 */
class RouteEngine(private val ctx: Context) {

    val baseDir: File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "brouter")
    val segmentDir = File(baseDir, "segments4")
    private val profileDir = File(baseDir, "profiles2")
    private val lock = Mutex()

    init {
        segmentDir.mkdirs()
        profileDir.mkdirs()
        for (name in listOf("lookups.dat", "moto.brf")) {
            val target = File(profileDir, name)
            ctx.assets.open("brouter/$name").use { input ->
                val bytes = input.readBytes()
                if (!target.exists() || !target.readBytes().contentEquals(bytes)) {
                    target.writeBytes(bytes)
                }
            }
        }
    }

    fun hasAnyMap(): Boolean = segmentDir.listFiles()?.any { it.name.endsWith(".rd5") } == true

    suspend fun route(points: List<GeoPoint>, mode: Mode, risks: List<RiskArea>): RouteResult =
        lock.withLock {
            withContext(Dispatchers.Default) { routeBlocking(points, mode, risks) }
        }

    private fun routeBlocking(points: List<GeoPoint>, mode: Mode, risks: List<RiskArea>): RouteResult {
        require(points.size >= 2)
        val collector = RoutingParamCollector()
        val lonlats = points.joinToString("|") { "${it.lon},${it.lat}" }
        val wpList = collector.getWayPointList(lonlats)

        val rc = RoutingContext()
        rc.localFunction = File(profileDir, "moto.brf").absolutePath
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        rc.memoryclass = am.largeMemoryClass.coerceIn(64, 512)

        val params = HashMap<String, String>()
        params["profile:modo"] = mode.code.toString()
        if (risks.isNotEmpty()) {
            // No modo Segura a margem em volta de cada area e 50% maior.
            val factor = if (mode == Mode.SEGURA) 1.5 else 1.0
            params["nogos"] = risks.joinToString("|") {
                "${it.point.lon},${it.point.lat},${(it.radiusM * factor).toInt()}"
            }
        }
        collector.setParams(rc, wpList, params)

        val t0 = System.currentTimeMillis()
        val engine = RoutingEngine(null, null, segmentDir, wpList, rc)
        engine.quite = true
        engine.doRun(180_000)
        android.util.Log.i("MotoRota", "rota ${mode.label}: ${System.currentTimeMillis() - t0} ms, erro=${engine.errorMessage}")

        engine.errorMessage?.let { throw RouteException(translateError(it)) }
        val track = engine.foundTrack ?: throw RouteException("Não foi possível calcular a rota.")
        return parse(mode, FormatJson(rc).format(track))
    }

    private fun translateError(msg: String): String = when {
        msg.contains("datafile", true) && msg.contains("not found", true) ->
            "O mapa desta região ainda não foi baixado. Toque em ⬇ Mapas."
        msg.contains("not mapped", true) || msg.contains("position not", true) ->
            "Um dos pontos está longe de qualquer rua. Escolha um ponto mais perto de uma via."
        msg.contains("island", true) || msg.contains("no track found", true) ->
            "Não achei estrada até um dos pontos. Ele pode estar na água, numa ilha ou longe de qualquer via: " +
                "mova ou remova esse ponto (✏️ Editar caminho)."
        msg.contains("timeout", true) || msg.contains("terminated", true) ->
            "O cálculo demorou demais. Tente adicionar uma parada no meio do caminho."
        else -> "Erro ao calcular a rota: $msg"
    }

    private fun parse(mode: Mode, json: String): RouteResult {
        val feature = JSONObject(json).getJSONArray("features").getJSONObject(0)
        val props = feature.getJSONObject("properties")
        val coords = feature.getJSONObject("geometry").getJSONArray("coordinates")

        val pts = ArrayList<GeoPoint>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            pts.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
        }
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + pts[i - 1].distanceTo(pts[i])

        val times = FloatArray(pts.size)
        props.optJSONArray("times")?.let { t ->
            for (i in 0 until minOf(t.length(), pts.size)) times[i] = t.getDouble(i).toFloat()
        }

        // estatisticas por trecho
        var total = 0L
        var highway = 0L
        var unpaved = 0L
        var badPaving = 0L
        val limits = ArrayList<SpeedSegment>()
        props.optJSONArray("messages")?.let { msgs ->
            val header = msgs.getJSONArray(0)
            var iDist = -1
            var iTags = -1
            for (i in 0 until header.length()) {
                when (header.getString(i)) {
                    "Distance" -> iDist = i
                    "WayTags" -> iTags = i
                }
            }
            if (iDist >= 0 && iTags >= 0) {
                for (r in 1 until msgs.length()) {
                    val row = msgs.getJSONArray(r)
                    val d = row.getString(iDist).toLongOrNull() ?: 0
                    val tags = row.getString(iTags)
                    val start = total.toDouble()
                    total += d
                    MAXSPEED.find(tags)?.groupValues?.get(1)?.toIntOrNull()?.let { v ->
                        val last = limits.lastOrNull()
                        if (last != null && last.kmh == v && last.endM == start) {
                            limits[limits.size - 1] = last.copy(endM = total.toDouble())
                        } else limits.add(SpeedSegment(start, total.toDouble(), v))
                    }
                    if (HIGHWAY.containsMatchIn(tags)) highway += d
                    if (isUnpaved(tags)) unpaved += d
                    else if (BAD_PAVING.containsMatchIn(tags)) badPaving += d
                }
            }
        }

        val hints = ArrayList<RouteHint>()
        props.optJSONArray("voicehints")?.let { vh ->
            for (i in 0 until vh.length()) {
                val h = vh.getJSONArray(i)
                hints.add(RouteHint(index = h.getInt(0), cmd = h.getInt(1), exit = h.getInt(2)))
            }
        }

        return RouteResult(
            mode = mode,
            points = pts,
            cumDist = cum,
            cumTime = times,
            distanceM = props.optString("track-length").toIntOrNull() ?: cum.lastOrNull()?.toInt() ?: 0,
            timeS = props.optString("total-time").toIntOrNull() ?: 0,
            highwayPct = if (total > 0) ((highway * 100) / total).toInt() else 0,
            unpavedM = unpaved.toInt(),
            badPavingM = badPaving.toInt(),
            hints = hints,
            speedLimits = limits,
        )
    }

    companion object {
        private val MAXSPEED = Regex("(?:^| )maxspeed=(\\d+)")
        private val HIGHWAY = Regex("highway=(motorway|trunk|primary)")
        private val UNPAVED_SURFACE =
            Regex("surface=(unpaved|gravel|ground|dirt|grass|compacted|sand|fine_gravel|earth|mud|clay|pebblestone|rock|stone|wood)\\b")
        private val BAD_PAVING = Regex("surface=(cobblestone|sett|paving_stones|grass_paver)\\b")

        fun isUnpaved(tags: String): Boolean {
            if (UNPAVED_SURFACE.containsMatchIn(tags)) return true
            if (tags.contains("surface=")) return false
            if (tags.contains("highway=track")) return !tags.contains("tracktype=grade1")
            return Regex("tracktype=grade[2-5]").containsMatchIn(tags)
        }
    }
}

package com.motorota

import android.app.Application
import android.location.Location
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motorota.data.Geocoder
import com.motorota.data.MapDownloader
import com.motorota.data.Place
import com.motorota.data.RiskStore
import com.motorota.nav.NavState
import com.motorota.nav.Navigator
import com.motorota.routing.GeoPoint
import com.motorota.routing.Mode
import com.motorota.routing.RiskArea
import com.motorota.routing.RouteException
import com.motorota.routing.RouteEngine
import com.motorota.routing.RouteResult
import com.motorota.routing.Waypoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val engine = RouteEngine(app)
    val downloader = MapDownloader(app, engine.segmentDir)
    private val riskStore = RiskStore(app)
    val radarStore = com.motorota.data.RadarStore(app)
    private val hazardStore = com.motorota.data.HazardStore(app)
    private val tripStore = com.motorota.data.TripStore(app)

    // ---------- estado da tela ----------
    var myLocation by mutableStateOf<GeoPoint?>(null); private set
    var speedKmh by mutableStateOf(0); private set
    var bearing by mutableStateOf(0f); private set
    var lastLocation by mutableStateOf<Location?>(null); private set

    /** partida escolhida no mapa; null = usar o GPS */
    var origin by mutableStateOf<Waypoint?>(null); private set
    var stops by mutableStateOf<List<Waypoint>>(emptyList()); private set
    var destination by mutableStateOf<Waypoint?>(null); private set

    var mode by mutableStateOf(Mode.DESEMPENHO); private set
    var routes by mutableStateOf<Map<Mode, RouteResult>>(emptyMap()); private set
    var routeErrors by mutableStateOf<Map<Mode, String>>(emptyMap()); private set
    var calculating by mutableStateOf<Set<Mode>>(emptySet()); private set

    var risks by mutableStateOf(riskStore.load()); private set
    var hazards by mutableStateOf(hazardStore.load()); private set
    var trips by mutableStateOf(tripStore.load()); private set

    /** tema do mapa: noite automatico das 18h as 6h */
    var night by mutableStateOf(java.time.LocalTime.now().hour.let { it >= 18 || it < 6 })

    /** radares e alertas em cima da rota em navegacao */
    var alongAlerts by mutableStateOf<List<com.motorota.nav.AlongAlert>>(emptyList()); private set
    var nextAlert by mutableStateOf<Pair<com.motorota.nav.AlongAlert, Double>?>(null); private set
    var currentLimit by mutableStateOf<Int?>(null); private set

    var navigating by mutableStateOf(false); private set
    var navState by mutableStateOf<NavState?>(null); private set
    var rerouting by mutableStateOf(false); private set

    var searchResults by mutableStateOf<List<Place>>(emptyList()); private set
    var searching by mutableStateOf(false); private set

    var message by mutableStateOf<String?>(null)
    private val prefs = app.getSharedPreferences("preferencias", android.content.Context.MODE_PRIVATE)

    /** voz ligada/desligada (fica salvo). Desligar corta na hora o que estiver falando. */
    var voiceOn by mutableStateOf(prefs.getBoolean("voz", true)); private set
    fun toggleVoice() {
        voiceOn = !voiceOn
        prefs.edit().putBoolean("voz", voiceOn).apply()
        if (!voiceOn) tts?.stop()
    }

    /** imagem de satelite no mapa (fica salvo) */
    var satellite by mutableStateOf(prefs.getBoolean("satelite", false)); private set
    fun toggleSatellite() {
        satellite = !satellite
        prefs.edit().putBoolean("satelite", satellite).apply()
    }

    // ---------- areas de risco conhecidas (vem com o app) ----------
    var knownRisksOn by mutableStateOf(prefs.getBoolean("areas_conhecidas", true)); private set
    var knownRisksVersion by mutableStateOf(0); private set
    fun toggleKnownRisks() {
        knownRisksOn = !knownRisksOn
        prefs.edit().putBoolean("areas_conhecidas", knownRisksOn).apply()
        knownRisksVersion++
        if (destination != null && !navigating) recalc(fit = false)
    }
    fun known(): List<com.motorota.data.KnownRisk> = if (knownRisksOn) com.motorota.data.KnownRisks.all else emptyList()
    fun corridors(): List<com.motorota.data.Corridor> = if (knownRisksOn) com.motorota.data.Corridors.all else emptyList()
    fun zones(): List<com.motorota.data.UrbanZone> = if (knownRisksOn) com.motorota.data.Corridors.zones else emptyList()
    /** trechos perigosos por onde a rota passa (para o painel) */
    fun corridorHits(r: RouteResult) = com.motorota.data.Corridors.hits(r, corridors())

    // ---------- autonomia do tanque ----------
    /** quantos km a moto roda com um tanque (0 = nao usar) */
    var tankKm by mutableStateOf(prefs.getInt("tanque_km", 0)); private set
    /** km rodados desde o ultimo "Abasteci" */
    var kmSinceFill by mutableStateOf(prefs.getFloat("km_desde_abastecer", 0f)); private set
    private var fuelWarnLevel = 0
    private var lastFuelSave = 0f
    val fuelLeftKm: Int? get() = if (tankKm > 0) (tankKm - kmSinceFill).toInt() else null
    fun setTank(km: Int) {
        tankKm = km.coerceIn(0, 2000)
        prefs.edit().putInt("tanque_km", tankKm).apply()
    }
    fun refuel() {
        kmSinceFill = 0f
        lastFuelSave = 0f
        fuelWarnLevel = 0
        prefs.edit().putFloat("km_desde_abastecer", 0f).apply()
        message = "⛽ Tanque cheio: $tankKm km de autonomia."
    }
    private fun addRiddenKm(km: Double) {
        if (tankKm <= 0) return
        kmSinceFill += km.toFloat()
        if (kmSinceFill - lastFuelSave > 0.5f) {
            lastFuelSave = kmSinceFill
            prefs.edit().putFloat("km_desde_abastecer", kmSinceFill).apply()
        }
        val left = tankKm - kmSinceFill
        val level = when {
            left <= 25 -> 2
            left <= 60 -> 1
            else -> 0
        }
        if (level > fuelWarnLevel) {
            fuelWarnLevel = level
            val txt = if (level == 2) "Reserva! Cerca de ${left.toInt().coerceAtLeast(0)} quilômetros de autonomia."
            else "Autonomia baixa: cerca de ${left.toInt()} quilômetros. Procure um posto."
            say(txt)
            message = "⛽ $txt"
        }
    }

    // ---------- computador de bordo ----------
    data class TripSummary(val distM: Double, val timeS: Long, val movingS: Long, val avgKmh: Int, val maxKmh: Int, val arrived: Boolean)
    var tripDistM by mutableStateOf(0.0); private set
    var tripMaxKmh by mutableStateOf(0); private set
    private var tripStartMs = 0L
    private var tripMovingS = 0.0
    var tripSummary by mutableStateOf<TripSummary?>(null)
    val tripAvgKmh: Int get() = if (tripMovingS > 30) (tripDistM / tripMovingS * 3.6).toInt() else 0
    val tripElapsedS: Long get() = if (tripStartMs > 0) (System.currentTimeMillis() - tripStartMs) / 1000 else 0

    // ---------- clima e por do sol no caminho ----------
    var routeWeather by mutableStateOf<com.motorota.data.RouteWeather?>(null); private set
    private var weatherJob: Job? = null
    private fun checkWeather(r: RouteResult, gen: Int) {
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            val w = try {
                com.motorota.data.Weather.along(r)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.w("MotoRota", "clima falhou: $e")
                com.motorota.data.Weather.offline(r)
            }
            if (gen == generation) routeWeather = w
        }
    }

    /** versao nova disponivel no GitHub (null = nenhuma) */
    var update by mutableStateOf<com.motorota.data.UpdateInfo?>(null)

    /** iniciar a navegacao assim que a rota (re)calculada ficar pronta */
    private var pendingStart = false

    /** muda sempre que o mapa precisa enquadrar a rota inteira */
    var fitRequest by mutableStateOf(0); private set
    var centerRequest by mutableStateOf(0); private set
    fun centerOnMe() {
        if (myLocation == null) message = "Ainda sem sinal de GPS." else centerRequest++
    }

    private var generation = 0
    /** fica pronto quando as areas e trechos de risco terminam de carregar (as rotas esperam por isso) */
    private val riskDataReady = kotlinx.coroutines.CompletableDeferred<Unit>()

    // ---------- transito ao vivo (TomTom) ----------
    /** ocorrencias de agora que estao no caminho (acidente, obra, alagamento...) */
    var liveIncidents by mutableStateOf<List<com.motorota.data.LiveIncident>>(emptyList()); private set
    /** frase curta para o painel: "consultando...", "nada no caminho", etc. */
    var trafficStatus by mutableStateOf<String?>(null); private set
    /** vias interditadas agora: viram bloqueios temporarios no calculo da rota */
    private var closures = listOf<RiskArea>()
    private var closureRounds = 0
    private var trafficJob: Job? = null

    private fun blocked() = risks + closures

    private fun resetTraffic() {
        trafficJob?.cancel()
        weatherJob?.cancel()
        routeWeather = null
        closures = emptyList()
        closureRounds = 0
        liveIncidents = emptyList()
        trafficStatus = null
    }

    /** Confere na TomTom o caminho escolhido; se tiver via interditada, desvia sozinho. */
    private fun checkTraffic(r: RouteResult, gen: Int) {
        if (!com.motorota.data.Traffic.enabled) return
        trafficJob?.cancel()
        trafficStatus = "📡 Consultando o trânsito de agora…"
        trafficJob = viewModelScope.launch {
            val found = try {
                com.motorota.data.Traffic.along(r)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.w("MotoRota", "transito TomTom falhou: $e")
                if (gen == generation) trafficStatus = "📡 Sem internet: trânsito ao vivo não consultado"
                return@launch
            }
            if (gen != generation) return@launch
            val newClosures = found.filter { inc ->
                inc.isClosure && closures.none { it.point.distanceTo(inc.point) < 30 }
            }
            if (newClosures.isNotEmpty() && closureRounds < 3) {
                closureRounds++
                closures = closures + newClosures.map {
                    RiskArea(System.nanoTime(), it.point, 15, "Interditada (TomTom)", System.currentTimeMillis())
                }
                val onde = newClosures.firstOrNull { it.where.isNotBlank() }?.where?.let { " ($it)" } ?: ""
                message = "⛔ Via interditada agora no caminho$onde. Recalculando para desviar…"
                if (navigating) say("Via interditada à frente. Recalculando.")
                if (navigating) myLocation?.let { reroute(it) } else recalc(fit = false)
                return@launch
            }
            liveIncidents = found
            val desvios = closures.size
            trafficStatus = when {
                found.isEmpty() && desvios == 0 -> "✅ Trânsito agora: nada de anormal no caminho"
                else -> listOfNotNull(
                    if (desvios > 0) "⛔ $desvios interdição(ões) evitada(s)" else null,
                    if (found.isNotEmpty()) "${found.size} ocorrência(s) agora no caminho" else null,
                ).joinToString(" · ")
            }
            if (navigating) currentRoute?.let { refreshAlertsAsync(it) }
        }
    }
    private var routeJob: Job? = null
    private var searchJob: Job? = null
    private var navigator: Navigator? = null
    private var offRouteCount = 0

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** muda quando os radares terminam de carregar */
    var radarsVersion by mutableStateOf(0); private set

    init {
        viewModelScope.launch {
            delay(4000) // deixa o app abrir primeiro
            update = com.motorota.data.Updater.check()
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // primeiro as areas/trechos de risco (rapido e a rota depende deles), depois os radares
            runCatching { com.motorota.data.KnownRisks.load(app) }
                .onFailure { android.util.Log.w("MotoRota", "areas de risco: $it") }
            runCatching { com.motorota.data.Corridors.load(app) }
                .onFailure { android.util.Log.w("MotoRota", "trechos perigosos: $it") }
            riskDataReady.complete(Unit)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { knownRisksVersion++ }
            radarStore.load()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { radarsVersion++ }
        }
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
                ttsReady = true
            }
        }
    }

    override fun onCleared() {
        tts?.shutdown()
    }

    val currentRoute: RouteResult? get() = routes[mode]

    // ---------- GPS ----------
    private var lastGpsFix = 0L
    private var lastFix: Pair<GeoPoint, Long>? = null
    private var smoothSpeed = -1.0

    fun onLocation(loc: Location) {
        // posicao pela rede (menos precisa) so e usada enquanto o GPS nao responde
        val now = System.currentTimeMillis()
        if (loc.provider == "gps") lastGpsFix = now
        else if (now - lastGpsFix < 15_000) return

        val p = GeoPoint(loc.latitude, loc.longitude)
        // alguns GPS nao informam velocidade/direcao: calcula pela posicao anterior
        val prev = lastFix
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val dt = prev?.let { (nowMs - it.second) / 1000.0 } ?: 0.0
        val moved = prev?.first?.distanceTo(p) ?: 0.0
        // acima de 90 m/s (~320 km/h) e salto de GPS, nao velocidade: descarta
        val derived = (if (prev != null && dt in 0.3..10.0) moved / dt else -1.0).let { if (it > 90) -1.0 else it }
        // usa a velocidade calculada pelas posicoes quando o GPS nao informa ou informa algo impossivel
        val gpsSpeedBad = !loc.hasSpeed() || loc.speed > 90f ||
            (loc.speed < 0.5f && derived > 3) ||
            (derived >= 0 && kotlin.math.abs(loc.speed - derived) > 15)
        if (derived >= 0 && gpsSpeedBad) {
            // media movel para nao "pular" quando as posicoes chegam em intervalos irregulares
            // uma moto nao ganha/perde mais que ~4 m/s (14 km/h) em um segundo: descarta saltos de GPS
            val limited = if (smoothSpeed < 0) derived else derived.coerceIn(smoothSpeed - 4 * dt, smoothSpeed + 4 * dt)
            smoothSpeed = if (smoothSpeed < 0) limited else smoothSpeed * 0.6 + limited * 0.4
            loc.speed = smoothSpeed.toFloat()
        }
        if ((!loc.hasBearing() || loc.bearing == 0f) && prev != null && moved > 3) {
            val k = Math.cos(Math.toRadians(p.lat))
            loc.bearing = ((Math.toDegrees(Math.atan2((p.lon - prev.first.lon) * k, p.lat - prev.first.lat)) + 360) % 360).toFloat()
        }
        lastFix = p to nowMs
        // distancia rodada: so conta trechos plausiveis (sem saltos de GPS) e com a moto andando
        if (prev != null && dt in 0.3..10.0 && derived in 1.0..90.0 && moved < 900) {
            addRiddenKm(moved / 1000.0)
            if (navigating) {
                tripDistM += moved
                tripMovingS += dt
            }
        }
        lastLocation = Location(loc).apply { if (speed < 1.5f || !hasBearing()) bearing = this@AppViewModel.bearing }
        myLocation = p
        if (loc.speed > 90f) loc.speed = 0f // valor impossivel e nada melhor para usar
        speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f).toInt() else 0
        if (BuildConfig.DEBUG) android.util.Log.d("MotoRotaGps", "prov=${loc.provider} hasSpeed=${loc.hasSpeed()} speed=${loc.speed} derived=$derived dt=$dt moved=$moved kmh=$speedKmh")
        if (loc.hasBearing() && loc.speed > 1.5f) bearing = loc.bearing
        if (navigating && speedKmh > tripMaxKmh && speedKmh < 320) tripMaxKmh = speedKmh

        val nav = navigator ?: return
        if (!navigating) return
        val st = nav.update(p)
        navState = st

        // paradas alcancadas saem da lista (para o recalculo nao voltar nelas)
        if (stops.isNotEmpty() && p.distanceTo(stops.first().point) < 100) {
            stops = stops.drop(1)
            say("Parada alcançada.")
        }

        if (st.arrived) {
            say("Você chegou ao destino.")
            stopNavigation(arrived = true)
            return
        }

        nav.speechFor(st, loc.speed)?.let { say(it) }

        // radares e alertas a frente
        currentLimit = nav.limitAt(st.alongM)
        val ahead = alongAlerts.firstOrNull { it.posM > st.alongM + 5 && it.posM - st.alongM < 2000 }
        nextAlert = ahead?.let { it to (it.posM - st.alongM).coerceAtLeast(0.0) }
        ahead?.let { a -> nav.alertSpeech(a, a.posM - st.alongM, loc.speed)?.let { say(it) } }

        val limit = if (loc.hasAccuracy()) maxOf(50f, loc.accuracy * 1.5f) else 50f
        if (st.offRouteM > limit) {
            offRouteCount++
            if (offRouteCount >= 3 && !rerouting) reroute(p)
        } else {
            offRouteCount = 0
        }
    }

    // ---------- pontos da rota ----------
    fun goTo(w: Waypoint) {
        resetTraffic()
        destination = w
        searchResults = emptyList()
        recalc(fit = true)
    }

    fun addStop(w: Waypoint) {
        if (destination == null) {
            goTo(w); return
        }
        stops = stops + w
        recalc(fit = true)
    }

    fun removeStop(i: Int) {
        pushUndo()
        stops = stops.filterIndexed { idx, _ -> idx != i }
        if (movingStop == i) movingStop = null
        recalcSoon()
    }

    fun setOriginHere(w: Waypoint?) {
        origin = w
        recalc(fit = true)
    }

    fun clearRoute() {
        stopNavigation()
        editMode = false
        movingStop = null
        undoStack.clear(); canUndo = false
        destination = null
        stops = emptyList()
        origin = null
        routes = emptyMap()
        routeErrors = emptyMap()
        calculating = emptySet()
        resetTraffic()
        generation++
    }

    fun selectMode(m: Mode) {
        mode = m
        if (routes[m] == null && routeErrors[m] == null && m !in calculating) recalc(fit = false)
        fitRequest++
    }

    private fun startPoint(): GeoPoint? = origin?.point ?: myLocation

    /** Calcula o modo escolhido primeiro e depois os outros dois (para comparar). */
    fun recalc(fit: Boolean) {
        val dest = destination ?: return
        val start = startPoint()
        if (start == null) {
            message = "Aguardando o GPS… ou toque e segure no mapa para escolher a partida."
            return
        }
        val pts = listOf(start) + stops.map { it.point } + dest.point
        val gen = ++generation
        routes = emptyMap()
        routeErrors = emptyMap()
        val order = listOf(mode) + Mode.entries.filter { it != mode }
        calculating = order.toSet()
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            for (m in order) {
                try {
                    riskDataReady.await()
                    val r = engine.route(pts, m, blocked(), known(), corridors(), zones())
                    if (gen != generation) return@launch
                    routes = routes + (m to r)
                    if (m == mode) {
                        checkTraffic(r, gen)
                        checkWeather(r, gen)
                    }
                    if (fit && m == mode) fitRequest++
                    if (m == mode && pendingStart) {
                        pendingStart = false
                        startNavigation()
                    }
                } catch (e: RouteException) {
                    if (gen != generation) return@launch
                    routeErrors = routeErrors + (m to (e.message ?: "erro"))
                } catch (e: Throwable) {
                    if (gen != generation) return@launch
                    routeErrors = routeErrors + (m to "Erro: ${e.message ?: e.javaClass.simpleName}")
                }
                calculating = calculating - m
            }
        }
    }

    // ---------- navegacao ----------
    fun startNavigation() {
        val r = currentRoute ?: return
        if (r.points.size < 2) {
            message = "A partida e o destino são o mesmo lugar. Adicione uma parada ou escolha outro destino."
            return
        }
        if (origin != null) {
            message = "A navegação usa o GPS como partida. Recalculando a partir da sua posição…"
            origin = null
        }
        navigator = Navigator(r)
        alongAlerts = emptyList()
        // radares/trechos ao longo da rota: calcula em segundo plano (nao trava o inicio)
        viewModelScope.launch {
            val a = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { alertsOn(r) }
            if (navigator?.route === r) alongAlerts = a
        }
        tripDistM = 0.0
        tripMaxKmh = 0
        tripMovingS = 0.0
        tripStartMs = System.currentTimeMillis()
        navigating = true
        offRouteCount = 0
        myLocation?.let { navState = navigator?.update(it) }
        say("Vamos lá. Rota ${r.mode.label.lowercase()}.")
    }

    fun stopNavigation(arrived: Boolean = false) {
        // resumo da viagem (so se rodou alguma coisa)
        if (navigating && tripDistM > 300) {
            tripSummary = TripSummary(
                distM = tripDistM,
                timeS = tripElapsedS,
                movingS = tripMovingS.toLong(),
                avgKmh = tripAvgKmh,
                maxKmh = tripMaxKmh,
                arrived = arrived,
            )
        }
        navigating = false
        navigator = null
        navState = null
        nextAlert = null
        currentLimit = null
        rerouting = false
    }

    private fun reroute(from: GeoPoint) {
        val dest = destination ?: return
        rerouting = true
        say("Recalculando.")
        val pts = listOf(from) + stops.map { it.point } + dest.point
        val m = mode
        viewModelScope.launch {
            try {
                riskDataReady.await()
                val r = engine.route(pts, m, blocked(), known(), corridors(), zones())
                routes = mapOf(m to r)
                routeErrors = emptyMap()
                navigator = Navigator(r)
                offRouteCount = 0
                checkTraffic(r, generation)
                refreshAlertsAsync(r)
            } catch (e: Throwable) {
                // pilotando: nada de instrucao comprida; tenta de novo nas proximas posicoes do GPS
                message = if (navigating) "Procurando caminho a partir daqui…" else e.message
                offRouteCount = 0
            } finally {
                rerouting = false
            }
        }
    }

    fun say(text: String) {
        if (!voiceOn || !ttsReady) return
        // so a fala mais recente importa: nao acumula avisos velhos na fila
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
    }

    // ---------- busca ----------
    fun search(q: String) {
        searchJob?.cancel()
        if (q.trim().length < 3) {
            searchResults = emptyList(); return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            searching = true
            try {
                searchResults = Geocoder.search(q, myLocation)
                if (searchResults.isEmpty()) message = "Nada encontrado para \"$q\"."
            } catch (e: Exception) {
                message = "Sem internet para buscar. Toque e segure no mapa para marcar o destino."
            } finally {
                searching = false
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchResults = emptyList()
    }

    // ---------- areas de risco ----------
    fun addRisk(p: GeoPoint, radiusM: Int, label: String) {
        risks = risks + RiskArea(System.currentTimeMillis(), p, radiusM, label, System.currentTimeMillis())
        riskStore.save(risks)
        message = "Área de risco marcada. As rotas vão desviar dela."
        if (destination != null) recalc(fit = false)
    }

    fun removeRisk(id: Long) {
        risks = risks.filter { it.id != id }
        riskStore.save(risks)
        if (destination != null) recalc(fit = false)
    }

    fun riskNear(p: GeoPoint): RiskArea? =
        risks.filter { it.point.distanceTo(p) <= it.radiusM }.minByOrNull { it.point.distanceTo(p) }

    // ---------- alertas tipo Waze ----------
    fun addHazard(type: com.motorota.data.HazardType, p: GeoPoint) {
        val h = com.motorota.data.Hazard(System.currentTimeMillis(), type, p, System.currentTimeMillis())
        hazards = hazards + h
        hazardStore.save(hazards)
        message = "${type.emoji} ${type.label} marcado. Vale por ${if (type.hoursValid >= 48) "${type.hoursValid / 24} dias" else "${type.hoursValid} h"}."
        currentRoute?.let { if (navigating) refreshAlertsAsync(it) }
    }

    fun removeHazard(id: Long) {
        hazards = hazards.filter { it.id != id }
        hazardStore.save(hazards)
    }

    fun hazardNear(p: GeoPoint): com.motorota.data.Hazard? =
        hazards.filter { it.point.distanceTo(p) < 120 }.minByOrNull { it.point.distanceTo(p) }

    // ---------- edicao do caminho ----------
    /** modo de edicao: cada toque no mapa vira um ponto de passagem */
    var editMode by mutableStateOf(false)
    /** parada escolhida para mover (o proximo toque no mapa define o novo lugar) */
    var movingStop by mutableStateOf<Int?>(null)
    private val undoStack = ArrayList<List<Waypoint>>()
    var canUndo by mutableStateOf(false); private set
    private var editJob: Job? = null

    private fun pushUndo() {
        undoStack.add(stops)
        if (undoStack.size > 50) undoStack.removeAt(0)
        canUndo = true
    }

    /** recalcula pouco depois do ultimo toque (para nao recalcular a cada ponto) */
    private fun recalcSoon() {
        editJob?.cancel()
        editJob = viewModelScope.launch {
            delay(if (editMode) 700 else 0)
            recalc(fit = false)
        }
    }

    /**
     * Coloca o ponto na posicao da sequencia onde ele "faz mais sentido"
     * (a que menos alonga o caminho), em vez de sempre no fim.
     */
    fun addViaSmart(p: GeoPoint) {
        val dest = destination ?: run { goTo(Waypoint(p, "Destino")); return }
        val start = startPoint() ?: run { addStop(Waypoint(p, "Ponto")); return }
        pushUndo()
        val seq = listOf(start) + stops.map { it.point } + dest.point
        var best = 0
        var bestCost = Double.MAX_VALUE
        for (i in 0 until seq.size - 1) {
            val c = seq[i].distanceTo(p) + p.distanceTo(seq[i + 1]) - seq[i].distanceTo(seq[i + 1])
            if (c < bestCost) { bestCost = c; best = i }
        }
        val l = stops.toMutableList()
        l.add(best, Waypoint(p, "Ponto de passagem"))
        stops = l
        recalcSoon()
    }

    fun moveStopTo(i: Int, p: GeoPoint) {
        if (i !in stops.indices) return
        pushUndo()
        stops = stops.toMutableList().also { it[i] = Waypoint(p, it[i].name) }
        movingStop = null
        recalcSoon()
    }

    fun moveStopDown(i: Int) {
        if (i < 0 || i >= stops.size - 1) return
        pushUndo()
        val l = stops.toMutableList()
        val t = l[i]; l[i] = l[i + 1]; l[i + 1] = t
        stops = l
        recalcSoon()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        stops = undoStack.removeAt(undoStack.size - 1)
        canUndo = undoStack.isNotEmpty()
        movingStop = null
        recalcSoon()
    }

    /** Botao "Iniciar" da edicao: sai da edicao e comeca a navegar (espera o recalculo se precisar). */
    fun startFromEditing() {
        editMode = false
        movingStop = null
        val pendingEdit = editJob?.isActive == true
        if (!pendingEdit && currentRoute != null && mode !in calculating) {
            startNavigation()
        } else {
            editJob?.cancel()
            pendingStart = true
            message = "Calculando o caminho editado… a navegação começa em seguida."
            recalc(fit = false)
        }
    }

    fun finishEditing() {
        editMode = false
        movingStop = null
        editJob?.cancel()
        recalc(fit = true)
    }

    // ---------- passeio personalizado ----------
    fun moveStopUp(i: Int) {
        if (i <= 0 || i >= stops.size) return
        val l = stops.toMutableList()
        val t = l[i]; l[i] = l[i - 1]; l[i - 1] = t
        pushUndo()
        stops = l
        recalcSoon()
    }

    /** Ida e volta: o destino vira parada e a rota termina onde comecou. */
    fun makeRoundTrip() {
        val dest = destination ?: return
        val start = origin ?: myLocation?.let { Waypoint(it, "Início") }
        if (start == null) {
            message = "Aguardando o GPS para saber onde é o início."; return
        }
        stops = stops + dest
        destination = Waypoint(start.point, "Volta: ${start.name}")
        recalc(fit = true)
    }

    fun saveTrip(name: String) {
        val dest = destination ?: return
        val t = com.motorota.data.SavedTrip(
            id = System.currentTimeMillis(),
            name = name.ifBlank { dest.name },
            origin = origin?.let { com.motorota.data.SavedPoint(it.point, it.name) },
            stops = stops.map { com.motorota.data.SavedPoint(it.point, it.name) },
            destination = com.motorota.data.SavedPoint(dest.point, dest.name),
            mode = mode.name,
        )
        trips = listOf(t) + trips
        tripStore.save(trips)
        message = "Passeio \"${t.name}\" salvo."
    }

    fun loadTrip(t: com.motorota.data.SavedTrip) {
        stopNavigation()
        origin = t.origin?.let { Waypoint(it.point, it.name) }
        stops = t.stops.map { Waypoint(it.point, it.name) }
        destination = Waypoint(t.destination.point, t.destination.name)
        mode = runCatching { Mode.valueOf(t.mode) }.getOrDefault(Mode.DESEMPENHO)
        recalc(fit = true)
    }

    fun deleteTrip(id: Long) {
        trips = trips.filter { it.id != id }
        tripStore.save(trips)
    }

    /** radares e alertas em cima da rota escolhida (para o resumo antes de sair) */
    /** recalcula os avisos ao longo da rota em segundo plano (sao contas pesadas em rota longa) */
    private fun refreshAlertsAsync(r: RouteResult) {
        viewModelScope.launch {
            val a = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { alertsOn(r) }
            if (navigator?.route === r) alongAlerts = a
        }
    }

    fun alertsOn(r: RouteResult) =
        com.motorota.nav.RouteAlerts.compute(r, radarStore, hazards, liveIncidents, known(), corridorHits(r))

    // ---------- mapas ----------
    var downloadProgress by mutableStateOf(MapDownloader.Progress(0, 0, 0, 0)); private set
    var mapsVersion by mutableStateOf(0); private set
    var hasMaps by mutableStateOf(engine.hasAnyMap()); private set

    fun pollDownloads() {
        val before = downloadProgress.pending
        downloadProgress = downloader.poll()
        if (downloadProgress.failed > 0) message = "Alguns mapas falharam. Toque em baixar de novo para tentar outra vez."
        if (before != downloadProgress.pending) mapsVersion++
        hasMaps = engine.hasAnyMap()
    }
}

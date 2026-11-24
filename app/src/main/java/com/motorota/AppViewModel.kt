package com.motorota

import android.app.Application
import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motorota.data.Corridor
import com.motorota.data.Corridors
import com.motorota.data.Geocoder
import com.motorota.data.Hazard
import com.motorota.data.HazardStore
import com.motorota.data.HazardType
import com.motorota.data.KnownRisk
import com.motorota.data.KnownRisks
import com.motorota.data.LiveIncident
import com.motorota.data.MapDownloader
import com.motorota.data.Place
import com.motorota.data.RadarStore
import com.motorota.data.RiskStore
import com.motorota.data.RouteWeather
import com.motorota.data.SavedPoint
import com.motorota.data.SavedTrip
import com.motorota.data.Traffic
import com.motorota.data.TripStore
import com.motorota.data.UpdateInfo
import com.motorota.data.Updater
import com.motorota.data.UrbanZone
import com.motorota.data.Weather
import com.motorota.nav.AlongAlert
import com.motorota.nav.Instructions
import com.motorota.nav.NavState
import com.motorota.nav.NavigationBridge
import com.motorota.nav.NavigationService
import com.motorota.nav.Navigator
import com.motorota.nav.RouteAlerts
import com.motorota.routing.GeoPoint
import com.motorota.routing.Mode
import com.motorota.routing.RiskArea
import com.motorota.routing.RouteEngine
import com.motorota.routing.RouteException
import com.motorota.routing.RouteResult
import com.motorota.routing.Waypoint
import com.motorota.routing.formatDistance
import com.motorota.routing.formatDuration
import com.motorota.ui.QuickAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val engine = RouteEngine(app)
    val downloader = MapDownloader(app, engine.segmentDir)
    private val riskStore = RiskStore(app)
    val radarStore = RadarStore(app)
    private val hazardStore = HazardStore(app)
    private val tripStore = TripStore(app)
    private val prefs = app.getSharedPreferences("preferencias", Context.MODE_PRIVATE)

    // ---------- Estado de GPS ----------
    var myLocation by mutableStateOf<GeoPoint?>(null); private set
    var speedKmh by mutableStateOf(0); private set
    var bearing by mutableStateOf(0f); private set
    var lastLocation by mutableStateOf<Location?>(null); private set
    var gpsLost by mutableStateOf(false); private set

    // ---------- Roteiro e Modos ----------
    var origin by mutableStateOf<Waypoint?>(null); private set
    var stops by mutableStateOf<List<Waypoint>>(emptyList()); private set
    var destination by mutableStateOf<Waypoint?>(null); private set

    var mode by mutableStateOf(Mode.DESEMPENHO); private set
    var routes by mutableStateOf<Map<Mode, RouteResult>>(emptyMap()); private set
    var routeErrors by mutableStateOf<Map<Mode, String>>(emptyMap()); private set
    var calculating by mutableStateOf<Set<Mode>>(emptySet()); private set
    val currentRoute: RouteResult? get() = routes[mode]

    // ---------- Dados Salvos ----------
    var risks by mutableStateOf(riskStore.load()); private set
    var hazards by mutableStateOf(hazardStore.load()); private set
    var trips by mutableStateOf(tripStore.load()); private set

    // ---------- Preferências Visuais e Áudio ----------
    var night by mutableStateOf(LocalTime.now().hour.let { it >= 18 || it < 6 })

    var showMapTip by mutableStateOf(!prefs.getBoolean("dica_vista", false)); private set
    fun dismissMapTip() {
        showMapTip = false
        prefs.edit().putBoolean("dica_vista", true).apply()
    }

    var voiceOn by mutableStateOf(prefs.getBoolean("voz", true)); private set
    fun toggleVoice() {
        voiceOn = !voiceOn
        prefs.edit().putBoolean("voz", voiceOn).apply()
        if (!voiceOn) tts?.stop()
    }

    var satellite by mutableStateOf(prefs.getBoolean("satelite", false)); private set
    fun toggleSatellite() {
        satellite = !satellite
        prefs.edit().putBoolean("satelite", satellite).apply()
    }

    private var pinnedMap by mutableStateOf(
        prefs.getStringSet("botoes_mapa", null)?.toSet() ?: QuickAction.DEFAULT_MAP
    )
    private var pinnedNav by mutableStateOf(
        prefs.getStringSet("botoes_nav", null)?.toSet() ?: QuickAction.DEFAULT_NAV
    )
    fun pinned(nav: Boolean): Set<String> = if (nav) pinnedNav else pinnedMap
    fun togglePin(nav: Boolean, id: String) {
        val cur = pinned(nav)
        val next = if (id in cur) cur - id else cur + id
        if (nav) pinnedNav = next else pinnedMap = next
        prefs.edit().putStringSet(if (nav) "botoes_nav" else "botoes_mapa", next).apply()
    }

    // ---------- Câmera e Enquadramento ----------
    var following by mutableStateOf(true)
    var recenterRequest by mutableStateOf(0); private set
    var overviewRequest by mutableStateOf(0); private set
    var fitRequest by mutableStateOf(0); private set
    var centerRequest by mutableStateOf(0); private set

    fun recenter() { following = true; recenterRequest++ }
    fun overview() { following = false; overviewRequest++ }
    fun fitRoute() { fitRequest++ }
    fun centerOnMe() {
        if (myLocation == null) message = "Ainda sem sinal de GPS." else centerRequest++
    }

    // ---------- Navegação Ativa ----------
    var alongAlerts by mutableStateOf<List<AlongAlert>>(emptyList()); private set
    var nextAlert by mutableStateOf<Pair<AlongAlert, Double>?>(null); private set
    var currentLimit by mutableStateOf<Int?>(null); private set
    var navigating by mutableStateOf(false); private set
    var navState by mutableStateOf<NavState?>(null); private set
    var rerouting by mutableStateOf(false); private set

    // ---------- Busca e Feedback ----------
    var searchResults by mutableStateOf<List<Place>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var update by mutableStateOf<UpdateInfo?>(null)

    // ---------- Áreas e Corredores de Risco ----------
    var knownRisksOn by mutableStateOf(prefs.getBoolean("areas_conhecidas", true)); private set
    var knownRisksVersion by mutableStateOf(0); private set
    var radarsVersion by mutableStateOf(0); private set
    private val riskDataReady = CompletableDeferred<Unit>()

    fun toggleKnownRisks() {
        knownRisksOn = !knownRisksOn
        prefs.edit().putBoolean("areas_conhecidas", knownRisksOn).apply()
        knownRisksVersion++
        if (destination != null && !navigating) recalc(fit = false)
    }
    fun known(): List<KnownRisk> = if (knownRisksOn) KnownRisks.all else emptyList()
    fun corridors(): List<Corridor> = if (knownRisksOn) Corridors.all else emptyList()
    fun zones(): List<UrbanZone> = if (knownRisksOn) Corridors.zones else emptyList()
    fun corridorHits(r: RouteResult) = Corridors.hits(r, corridors())

    // ---------- Autonomia do Tanque ----------
    var tankKm by mutableStateOf(prefs.getInt("tanque_km", 0)); private set
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
        message = "Tanque cheio: $tankKm km de autonomia."
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
            say(txt, urgent = false)
            message = txt
        }
    }

    // ---------- Computador de Bordo ----------
    data class TripSummary(
        val distM: Double,
        val timeS: Long,
        val movingS: Long,
        val avgKmh: Int,
        val maxKmh: Int,
        val arrived: Boolean,
    )

    var tripDistM by mutableStateOf(0.0); private set
    var tripMaxKmh by mutableStateOf(0); private set
    private var tripStartElapsedMs = 0L
    private var tripMovingS = 0.0
    var tripSummary by mutableStateOf<TripSummary?>(null)
    val tripAvgKmh: Int get() = if (tripMovingS > 30) (tripDistM / tripMovingS * 3.6).toInt() else 0
    val tripElapsedS: Long get() = if (tripStartElapsedMs > 0) (SystemClock.elapsedRealtime() - tripStartElapsedMs) / 1000 else 0

    // ---------- Clima e Trânsito ao Vivo ----------
    var routeWeather by mutableStateOf<RouteWeather?>(null); private set
    var liveIncidents by mutableStateOf<List<LiveIncident>>(emptyList()); private set
    var trafficStatus by mutableStateOf<String?>(null); private set
    private var closures = listOf<RiskArea>()
    private var closureRounds = 0

    private var generation = 0
    private var pendingStart = false
    private var routeJob: Job? = null
    private var searchJob: Job? = null
    private var weatherJob: Job? = null
    private var trafficJob: Job? = null
    private var editJob: Job? = null
    private var navigator: Navigator? = null
    private var offRouteCount = 0
    private var lastNotifUpdateMs = 0L

    // ---------- Voz (TTS com proteção contra corte de manobras) ----------
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastUrgentSpeechMs = 0L

    init {
        NavigationBridge.onLocation = { loc -> onLocation(loc) }
        NavigationBridge.onStopRequested = { stopNavigation(arrived = false) }

        viewModelScope.launch {
            delay(4000)
            update = Updater.check()
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { KnownRisks.load(app) }.onFailure { Log.w("MotoRota", "areas de risco: $it") }
            runCatching { Corridors.load(app) }.onFailure { Log.w("MotoRota", "trechos perigosos: $it") }
            riskDataReady.complete(Unit)
            withContext(Dispatchers.Main) { knownRisksVersion++ }
            radarStore.load()
            withContext(Dispatchers.Main) { radarsVersion++ }
        }
        // Watchdog: zera velocidade travada se perder GPS (ex.: túnel ou parada completa)
        viewModelScope.launch {
            while (true) {
                delay(2000)
                val prev = lastFix
                if (prev != null) {
                    val silenceMs = SystemClock.elapsedRealtime() - prev.second
                    if (silenceMs > 5000 && speedKmh > 0) {
                        speedKmh = 0
                        smoothSpeed = -1.0
                    }
                    gpsLost = navigating && silenceMs > 10_000
                }
            }
        }
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
                ttsReady = true
            }
        }
    }

    override fun onCleared() {
        NavigationBridge.onLocation = null
        NavigationBridge.onStopRequested = null
        NavigationService.stop(getApplication())
        tts?.shutdown()
    }

    // ---------- Processamento de GPS ----------
    private var lastGpsFix = 0L
    private var lastFix: Pair<GeoPoint, Long>? = null
    private var smoothSpeed = -1.0

    fun onLocation(loc: Location) {
        val nowWall = System.currentTimeMillis()
        if (loc.provider == LocationManagerProviderGps) lastGpsFix = nowWall
        else if (nowWall - lastGpsFix < 15_000) return

        val nowMs = SystemClock.elapsedRealtime()
        val prev = lastFix
        if (prev != null && nowMs - prev.second < 250) return // evita duplicidade entre Activity e Service

        val p = GeoPoint(loc.latitude, loc.longitude)
        val dt = prev?.let { (nowMs - it.second) / 1000.0 } ?: 0.0
        val moved = prev?.first?.distanceTo(p) ?: 0.0

        val derived = (if (prev != null && dt in 0.3..10.0) moved / dt else -1.0).let { if (it > 90) -1.0 else it }
        val gpsSpeedBad = !loc.hasSpeed() || loc.speed > 90f ||
            (loc.speed < 0.5f && derived > 3) ||
            (derived >= 0 && abs(loc.speed - derived) > 15)

        if (derived >= 0 && gpsSpeedBad) {
            val limited = if (smoothSpeed < 0) derived else derived.coerceIn(smoothSpeed - 4 * dt, smoothSpeed + 4 * dt)
            smoothSpeed = if (smoothSpeed < 0) limited else smoothSpeed * 0.6 + limited * 0.4
            loc.speed = smoothSpeed.toFloat()
        }
        if ((!loc.hasBearing() || loc.bearing == 0f) && prev != null && moved > 3) {
            val k = cos(Math.toRadians(p.lat))
            loc.bearing = ((Math.toDegrees(atan2((p.lon - prev.first.lon) * k, p.lat - prev.first.lat)) + 360) % 360).toFloat()
        }

        lastFix = p to nowMs
        gpsLost = false

        if (prev != null && dt in 0.3..10.0 && derived in 1.0..90.0 && moved < 900) {
            addRiddenKm(moved / 1000.0)
            if (navigating) {
                tripDistM += moved
                tripMovingS += dt
            }
        }

        lastLocation = Location(loc).apply { if (speed < 1.5f || !hasBearing()) bearing = this@AppViewModel.bearing }
        myLocation = p
        if (loc.speed > 90f) loc.speed = 0f
        speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f).toInt() else 0
        if (loc.hasBearing() && loc.speed > 1.5f) bearing = loc.bearing
        if (navigating && speedKmh > tripMaxKmh && speedKmh < 320) tripMaxKmh = speedKmh

        val nav = navigator ?: return
        if (!navigating) return
        val st = nav.update(p)
        navState = st

        if (stops.isNotEmpty() && p.distanceTo(stops.first().point) < 100) {
            stops = stops.drop(1)
            say("Parada alcançada.", urgent = false)
        }

        if (st.arrived) {
            say("Você chegou ao destino.", urgent = true)
            stopNavigation(arrived = true)
            return
        }

        nav.speechFor(st, loc.speed)?.let { say(it, urgent = true) }

        currentLimit = nav.limitAt(st.alongM)
        val ahead = alongAlerts.firstOrNull { it.posM > st.alongM + 5 && it.posM - st.alongM < 2000 }
        nextAlert = ahead?.let { it to (it.posM - st.alongM).coerceAtLeast(0.0) }
        ahead?.let { a -> nav.alertSpeech(a, a.posM - st.alongM, loc.speed)?.let { say(it, urgent = false) } }

        // Atualiza notificação de 1º plano a cada ~5s
        if (nowMs - lastNotifUpdateMs > 5000) {
            lastNotifUpdateMs = nowMs
            val actionTxt = st.nextHint?.let {
                "${formatDistance(st.distToHintM)} · ${Instructions.text(it).replaceFirstChar { c -> c.uppercase() }}"
            } ?: "Siga até o destino"
            val subTxt = "Faltam ${formatDistance(st.remainingM)} (${formatDuration(st.remainingS)})"
            NavigationService.update(getApplication(), actionTxt, subTxt)
        }

        val limit = if (loc.hasAccuracy()) maxOf(50f, loc.accuracy * 1.5f) else 50f
        if (st.offRouteM > limit) {
            offRouteCount++
            if (offRouteCount >= 3 && !rerouting) reroute(p)
        } else {
            offRouteCount = 0
        }
    }

    // ---------- Clima e Trânsito ----------
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

    private fun hasInternet(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun shortReason(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "demorou para responder"
        is UnknownHostException -> "sem conexão com o servidor"
        else -> e.message?.take(40) ?: e.javaClass.simpleName
    }

    private fun checkWeather(r: RouteResult, gen: Int) {
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            val w = try {
                Weather.along(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("MotoRota", "clima falhou: $e")
                Weather.offline(r)
            }
            if (gen == generation) routeWeather = w
        }
    }

    private fun checkTraffic(r: RouteResult, gen: Int) {
        if (!Traffic.enabled) return
        trafficJob?.cancel()
        trafficStatus = "Consultando trânsito ao vivo…"
        trafficJob = viewModelScope.launch {
            val found = try {
                Traffic.along(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("MotoRota", "transito TomTom falhou: $e")
                if (gen == generation) {
                    trafficStatus = if (!hasInternet()) "Offline · trânsito ao vivo indisponível"
                    else "Trânsito indisponível (${shortReason(e)})"
                }
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
                message = "Via interditada no caminho$onde. Desviando…"
                if (navigating) say("Via interditada à frente. Recalculando.", urgent = true)
                if (navigating) myLocation?.let { reroute(it) } else recalc(fit = false)
                return@launch
            }
            liveIncidents = found
            val desvios = closures.size
            trafficStatus = when {
                found.isEmpty() && desvios == 0 ->
                    if (Traffic.partial) "Trânsito livre (consulta parcial)"
                    else "Trânsito livre no trajeto"
                else -> listOfNotNull(
                    if (desvios > 0) "$desvios interdição(ões) evitada(s)" else null,
                    if (found.isNotEmpty()) "${found.size} ocorrência(s) na via" else null,
                ).joinToString(" · ")
            }
            if (navigating) currentRoute?.let { refreshAlertsAsync(it) }
        }
    }

    // ---------- Pontos da Rota ----------
    fun goTo(w: Waypoint) {
        dismissMapTip()
        resetTraffic()
        destination = w
        searchResults = emptyList()
        recalc(fit = true)
    }

    fun addStop(w: Waypoint) {
        if (destination == null) {
            goTo(w); return
        }
        pushUndo()
        stops = stops + w
        recalc(fit = true)
    }

    fun removeStop(i: Int) {
        if (i !in stops.indices) return
        pushUndo()
        stops = stops.filterIndexed { idx, _ -> idx != i }
        if (movingStop == i) movingStop = null
        recalcSoon()
    }

    fun setOriginHere(w: Waypoint?) {
        dismissMapTip()
        origin = w
        if (destination != null) recalc(fit = true)
    }

    fun swapEndpoints() {
        val dest = destination ?: return
        val orig = origin ?: myLocation?.let { Waypoint(it, "Localização inicial") } ?: run {
            message = "Sem sinal de GPS para inverter a rota."
            return
        }
        pushUndo()
        stops = stops.reversed()
        origin = dest
        destination = orig
        resetTraffic()
        recalc(fit = true)
    }

    fun clearRoute() {
        stopNavigation()
        pendingStart = false
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
        if (mode == m) {
            fitRequest++
            return
        }
        mode = m
        val r = routes[m]
        if (r != null) {
            checkTraffic(r, generation)
            checkWeather(r, generation)
        } else if (routeErrors[m] == null && m !in calculating) {
            recalc(fit = false)
        }
        fitRequest++
    }

    private fun startPoint(): GeoPoint? = origin?.point ?: myLocation

    /** Calcula o modo escolhido primeiro e depois os outros dois para comparação. */
    fun recalc(fit: Boolean) {
        val dest = destination ?: return
        val start = startPoint()
        if (start == null) {
            pendingStart = false
            message = "Aguardando GPS… ou toque e segure no mapa para escolher a partida."
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
                    if (m == mode) pendingStart = false
                    routeErrors = routeErrors + (m to (e.message ?: "erro"))
                } catch (e: Throwable) {
                    if (gen != generation) return@launch
                    if (m == mode) pendingStart = false
                    routeErrors = routeErrors + (m to "Erro: ${e.message ?: e.javaClass.simpleName}")
                }
                calculating = calculating - m
            }
        }
    }

    // ---------- Navegação ----------
    fun startNavigation() {
        val r = currentRoute ?: return
        if (r.points.size < 2) {
            message = "A partida e o destino são o mesmo lugar. Adicione uma parada ou escolha outro destino."
            return
        }
        // Se havia partida manual longe do GPS atual, recalcula de verdade a partir do GPS antes de guiar
        val customOrigin = origin
        if (customOrigin != null) {
            val gps = myLocation
            if (gps == null) {
                message = "Sem sinal de GPS para iniciar a navegação."
                return
            }
            origin = null
            if (gps.distanceTo(customOrigin.point) > 80) {
                pendingStart = true
                message = "Recalculando a rota a partir do seu GPS atual…"
                recalc(fit = false)
                return
            }
        }

        navigator = Navigator(r)
        alongAlerts = emptyList()
        refreshAlertsAsync(r)

        tripDistM = 0.0
        tripMaxKmh = 0
        tripMovingS = 0.0
        tripStartElapsedMs = SystemClock.elapsedRealtime()
        gpsLost = false
        following = true
        navigating = true
        offRouteCount = 0
        myLocation?.let { navState = navigator?.update(it) }

        NavigationService.start(
            getApplication(),
            "Rota ${r.mode.label}",
            "Faltam ${formatDistance(r.distanceM.toDouble())} (${formatDuration(r.timeS)})",
        )
        say("Vamos lá. Rota ${r.mode.label.lowercase()}.", urgent = true)
    }

    fun stopNavigation(arrived: Boolean = false) {
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
        pendingStart = false
        navigating = false
        navigator = null
        navState = null
        nextAlert = null
        currentLimit = null
        rerouting = false
        gpsLost = false
        NavigationService.stop(getApplication())
    }

    private fun reroute(from: GeoPoint) {
        val dest = destination ?: return
        rerouting = true
        say("Recalculando.", urgent = true)
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
                message = if (navigating) "Procurando caminho a partir daqui…" else e.message
                offRouteCount = 0
            } finally {
                rerouting = false
            }
        }
    }

    fun say(text: String, urgent: Boolean = true) {
        if (!voiceOn || !ttsReady) return
        val now = SystemClock.elapsedRealtime()
        val queueMode = if (!urgent && now - lastUrgentSpeechMs < 4000) {
            // Não corta uma instrução de curva recém-iniciada: coloca o alerta logo em seguida
            TextToSpeech.QUEUE_ADD
        } else {
            if (urgent) lastUrgentSpeechMs = now
            TextToSpeech.QUEUE_FLUSH
        }
        tts?.speak(text, queueMode, null, text.hashCode().toString())
    }

    // ---------- Busca ----------
    fun search(q: String) {
        searchJob?.cancel()
        if (q.trim().length < 3) {
            searchResults = emptyList(); return
        }
        searchJob = viewModelScope.launch {
            delay(320)
            searching = true
            try {
                searchResults = Geocoder.search(q, myLocation)
                if (searchResults.isEmpty()) message = "Nada encontrado para \"$q\"."
            } catch (_: Exception) {
                message = "Sem internet para buscar. Toque e segure no mapa para marcar o destino."
            } finally {
                searching = false
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searching = false
        searchResults = emptyList()
    }

    // ---------- Áreas de Risco e Alertas ----------
    fun addRisk(p: GeoPoint, radiusM: Int, label: String) {
        dismissMapTip()
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

    fun addHazard(type: HazardType, p: GeoPoint) {
        dismissMapTip()
        val now = System.currentTimeMillis()
        val h = Hazard(now, type, p, now)
        hazards = hazards.filter { it.expiresAt > now } + h
        hazardStore.save(hazards)
        val validTxt = if (type.hoursValid >= 48) "${type.hoursValid / 24} dias" else "${type.hoursValid} h"
        message = "${type.emoji} ${type.label} registrado (válido por $validTxt)."
        currentRoute?.let { if (navigating) refreshAlertsAsync(it) }
    }

    fun removeHazard(id: Long) {
        hazards = hazards.filter { it.id != id }
        hazardStore.save(hazards)
        currentRoute?.let { if (navigating) refreshAlertsAsync(it) }
    }

    fun hazardNear(p: GeoPoint): Hazard? =
        hazards.filter { it.expiresAt > System.currentTimeMillis() && it.point.distanceTo(p) < 120 }
            .minByOrNull { it.point.distanceTo(p) }

    // ---------- Edição Inteligente do Caminho ----------
    var editMode by mutableStateOf(false)
    var movingStop by mutableStateOf<Int?>(null)
    private val undoStack = ArrayList<List<Waypoint>>()
    var canUndo by mutableStateOf(false); private set

    private fun pushUndo() {
        undoStack.add(stops)
        if (undoStack.size > 50) undoStack.removeAt(0)
        canUndo = true
    }

    private fun recalcSoon() {
        editJob?.cancel()
        editJob = viewModelScope.launch {
            delay(if (editMode) 650 else 0)
            recalc(fit = false)
        }
    }

    /** Insere o ponto na posição da sequência que menos alonga o caminho. */
    fun addViaSmart(p: GeoPoint, name: String = "Ponto de passagem") {
        val dest = destination ?: run { goTo(Waypoint(p, if (name == "Ponto de passagem") "Destino" else name)); return }
        val start = startPoint() ?: run { addStop(Waypoint(p, name)); return }
        pushUndo()
        val seq = listOf(start) + stops.map { it.point } + dest.point
        var best = 0
        var bestCost = Double.MAX_VALUE
        for (i in 0 until seq.size - 1) {
            val c = seq[i].distanceTo(p) + p.distanceTo(seq[i + 1]) - seq[i].distanceTo(seq[i + 1])
            if (c < bestCost) { bestCost = c; best = i }
        }
        val l = stops.toMutableList()
        l.add(best, Waypoint(p, name))
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

    fun moveStopUp(i: Int) {
        if (i <= 0 || i >= stops.size) return
        pushUndo()
        val l = stops.toMutableList()
        val t = l[i]; l[i] = l[i - 1]; l[i - 1] = t
        stops = l
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

    // ---------- Passeios Salvos ----------
    fun makeRoundTrip() {
        val dest = destination ?: return
        val start = origin ?: myLocation?.let { Waypoint(it, "Início") }
        if (start == null) {
            message = "Aguardando o GPS para saber onde é o início."; return
        }
        pushUndo()
        stops = stops + dest
        destination = Waypoint(start.point, "Volta: ${start.name}")
        recalc(fit = true)
    }

    fun saveTrip(name: String) {
        val dest = destination ?: return
        val t = SavedTrip(
            id = System.currentTimeMillis(),
            name = name.trim().ifBlank { dest.name },
            origin = origin?.let { SavedPoint(it.point, it.name) },
            stops = stops.map { SavedPoint(it.point, it.name) },
            destination = SavedPoint(dest.point, dest.name),
            mode = mode.name,
        )
        trips = listOf(t) + trips
        tripStore.save(trips)
        message = "Passeio \"${t.name}\" salvo."
    }

    fun loadTrip(t: SavedTrip) {
        stopNavigation()
        dismissMapTip()
        resetTraffic()
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

    private fun refreshAlertsAsync(r: RouteResult) {
        viewModelScope.launch {
            val a = withContext(Dispatchers.Default) { alertsOn(r) }
            if (navigator?.route === r) alongAlerts = a
        }
    }

    fun alertsOn(r: RouteResult) =
        RouteAlerts.compute(r, radarStore, hazards, liveIncidents, known(), corridorHits(r))

    // ---------- Mapas Offline ----------
    var downloadProgress by mutableStateOf(MapDownloader.Progress(0, 0, 0, 0)); private set
    var mapsVersion by mutableStateOf(0); private set
    var hasMaps by mutableStateOf(engine.hasAnyMap()); private set

    fun pollDownloads() {
        val before = downloadProgress.pending
        downloadProgress = downloader.poll()
        if (downloadProgress.failed > 0) message = "Alguns mapas falharam. Toque em baixar novamente."
        if (before != downloadProgress.pending) {
            mapsVersion++
            hasMaps = engine.hasAnyMap()
            // Se acabou de baixar mapas e já tinha destino aguardando, recalcula sozinho!
            if (downloadProgress.pending == 0 && hasMaps && destination != null && currentRoute == null && !navigating) {
                recalc(fit = true)
            }
        } else {
            hasMaps = engine.hasAnyMap()
        }
    }

    companion object {
        private const val LocationManagerProviderGps = "gps"
    }
}

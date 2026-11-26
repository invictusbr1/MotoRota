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
import android.speech.tts.UtteranceProgressListener
import com.motorota.assistant.Jarvis
import com.motorota.assistant.JarvisVoice
import com.motorota.assistant.MessageListener
import com.motorota.assistant.PhoneActions
import com.motorota.assistant.PiperVoice
import com.motorota.assistant.Tool
import com.motorota.intercom.CallState
import com.motorota.intercom.Intercom
import android.media.AudioAttributes
import com.motorota.data.Corridor
import com.motorota.data.Keys
import com.motorota.data.Http
import com.motorota.data.BikePhoto
import com.motorota.data.BikeProfile
import com.motorota.data.Bikes
import com.motorota.data.RouteSearch
import com.motorota.data.SelfInstaller
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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
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

    init {
        Keys.init(app)
    }

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
    init {
        // versão com Jarvis: coloca o botão do microfone na tela uma vez (dá para tirar no menu)
        // menu novo (três pontinhos): a tela volta para os atalhos enxutos, uma única vez
        if (!prefs.getBoolean("tela_limpa_v1", false)) {
            pinnedMap = QuickAction.DEFAULT_MAP
            pinnedNav = QuickAction.DEFAULT_NAV
            prefs.edit()
                .putBoolean("tela_limpa_v1", true)
                .putBoolean("pin_jarvis_v1", true)
                .putStringSet("botoes_mapa", pinnedMap)
                .putStringSet("botoes_nav", pinnedNav)
                .apply()
        }
        if (!prefs.getBoolean("pin_jarvis_v1", false)) {
            pinnedMap = pinnedMap + QuickAction.JARVIS.id
            pinnedNav = pinnedNav + QuickAction.JARVIS.id
            prefs.edit()
                .putBoolean("pin_jarvis_v1", true)
                .putStringSet("botoes_mapa", pinnedMap)
                .putStringSet("botoes_nav", pinnedNav)
                .apply()
        }
    }
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
    /** Rotas pelo mapa TomTom (online, atualizado toda semana); sem internet usa o mapa offline. */
    var freshMapOn by mutableStateOf(prefs.getBoolean("mapa_tomtom", true)); private set
    fun toggleFreshMap() {
        freshMapOn = !freshMapOn
        prefs.edit().putBoolean("mapa_tomtom", freshMapOn).apply()
        if (destination != null && !navigating) recalc(fit = false)
    }

    /**
     * O CAMINHO sempre sai do motor de segurança (BRouter + áreas/zonas/trechos de risco).
     * Com internet, a TomTom só redesenha esse mesmo caminho no mapa atualizado dela, e o
     * desenho é descartado se sair do caminho seguro.
     */
    private suspend fun computeRoute(pts: List<GeoPoint>, m: Mode): RouteResult {
        val tt = com.motorota.routing.TomTomRouter
        if (engine.hasAnyMap()) {
            val base = engine.route(pts, m, blocked(), known(), corridors(), zones())
            if (freshMapOn && tt.available) {
                try {
                    tt.refine(pts, base, known(), zones())?.let { return it }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
            }
            return base
        }
        if (tt.available) {
            val r = tt.route(pts, m, blocked(), known(), zones())
            return r.copy(
                note = "⚠️ Sem o mapa offline baixado: esta rota NÃO passou pelo motor de segurança completo " +
                    "(desvio de comunidades e zonas de risco). Baixe o mapa do Brasil no menu.",
            )
        }
        throw RouteException("Baixe o mapa offline no menu para calcular rotas.")
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
    // ---------- Piloto e moto ----------
    var riderName by mutableStateOf(prefs.getString("nome_piloto", "") ?: ""); private set
    /** primeira abertura: pede o nome e a moto */
    var showWelcome by mutableStateOf(!prefs.getBoolean("boas_vindas_ok", false)); private set
    var bike by mutableStateOf(BikeProfile.fromJson(prefs.getString("moto", null))); private set
    private var lastBikeSave = 0.0

    /** tem como calcular a autonomia (moto configurada ou autonomia digitada) */
    val hasFuelTracking: Boolean get() = bike != null || tankKm > 0

    val fuelLeftKm: Int?
        get() = bike?.leftKm ?: if (tankKm > 0) (tankKm - kmSinceFill).toInt() else null

    private fun saveBike(b: BikeProfile?) {
        bike = b
        prefs.edit().putString("moto", b?.toJson()).apply()
    }

    fun finishWelcome(name: String, modelId: String?) {
        val n = name.trim()
        if (n.isNotEmpty()) {
            riderName = n
            prefs.edit().putString("nome_piloto", n).apply()
            // o mesmo nome vale para o intercomunicador
            viewModelScope.launch { runCatching { intercom.setName(n) } }
        }
        if (modelId != null && bike?.modelId != modelId) chooseBike(modelId)
        showWelcome = false
        prefs.edit().putBoolean("boas_vindas_ok", true).apply()
    }

    fun saveRiderName(name: String) {
        riderName = name.trim()
        prefs.edit().putString("nome_piloto", riderName).apply()
    }

    fun chooseBike(modelId: String) {
        val old = bike
        saveBike(BikeProfile(modelId = modelId, photo = old?.photo, autoAdjust = old?.autoAdjust ?: true))
        message = "Moto escolhida: ${bike?.name}. Toque em \"Abasteci\" quando encher o tanque."
    }

    fun setCustomBike(name: String, tankL: Double, kmL: Double) {
        val old = bike
        saveBike(BikeProfile(modelId = "outra", customName = name.trim(), customTankL = tankL, customKmL = kmL, photo = old?.photo))
    }

    fun setBikePhoto(uri: android.net.Uri) {
        val b = bike ?: return
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { BikePhoto.save(getApplication(), uri) }
            if (path != null) saveBike(b.copy(photo = path)) else message = "Não consegui abrir essa foto."
        }
    }

    fun toggleBikeAuto() {
        val b = bike ?: return
        saveBike(b.copy(autoAdjust = !b.autoAdjust))
    }

    fun removeBike() = saveBike(null)

    /**
     * Abasteci. Se informar quantos litros entraram, o app compara com o que ele estimou
     * e corrige o consumo da SUA moto (fica mais preciso a cada abastecimento).
     */
    fun refuelBike(litersIn: Double?) {
        val b = bike ?: return
        var nb = b.copy(usedL = 0.0)
        if (litersIn != null && litersIn > 1 && b.usedL > 2 && b.autoAdjust) {
            // estimou gastar usedL e entraram litersIn: >1 = a moto rendeu mais do que eu achava
            val ratio = (b.usedL / litersIn).coerceIn(0.6, 1.5)
            val weight = if (b.learnedFills == 0) 0.6 else 0.35
            nb = nb.copy(learned = (b.learned * (1 - weight + weight * ratio)).coerceIn(0.6, 1.5), learnedFills = b.learnedFills + 1)
        }
        saveBike(nb)
        kmSinceFill = 0f
        lastFuelSave = 0f
        fuelWarnLevel = 0
        prefs.edit().putFloat("km_desde_abastecer", 0f).apply()
        message = "Tanque cheio: cerca de ${nb.fullRangeKm} km de autonomia (±${nb.marginPct}%)."
    }

    fun setTank(km: Int) {
        tankKm = km.coerceIn(0, 2000)
        prefs.edit().putInt("tanque_km", tankKm).apply()
    }

    fun refuel() {
        if (bike != null) {
            refuelBike(null); return
        }
        kmSinceFill = 0f
        lastFuelSave = 0f
        fuelWarnLevel = 0
        prefs.edit().putFloat("km_desde_abastecer", 0f).apply()
        message = "Tanque cheio: $tankKm km de autonomia."
    }

    private fun addRiddenKm(km: Double, kmh: Double) {
        val b = bike
        if (b == null && tankKm <= 0) return
        kmSinceFill += km.toFloat()
        if (b != null) {
            // gasto estimado deste trecho, considerando a velocidade (vento e anda-e-para mudam o consumo)
            val factor = if (b.autoAdjust) Bikes.speedFactor(kmh) else 1.0
            val used = b.usedL + km / (b.kmL * factor)
            bike = b.copy(usedL = used)
            if (used - lastBikeSave > 0.05) {
                lastBikeSave = used
                prefs.edit().putString("moto", bike?.toJson()).apply()
            }
        }
        if (kmSinceFill - lastFuelSave > 0.5f) {
            lastFuelSave = kmSinceFill
            prefs.edit().putFloat("km_desde_abastecer", kmSinceFill).apply()
        }
        val left = (fuelLeftKm ?: return).toFloat()
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
    private val speechDone = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var speechSeq = 0

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
                tts?.setPitch(0.9f) // um pouco mais grave
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { speechDone.remove(it)?.complete(Unit) }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { speechDone.remove(it)?.complete(Unit) }
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        utteranceId?.let { speechDone.remove(it)?.complete(Unit) }
                    }
                })
                ttsReady = true
            }
        }
    }

    override fun onCleared() {
        NavigationBridge.onLocation = null
        NavigationBridge.onStopRequested = null
        NavigationService.stop(getApplication())
        if (jarvisDelegate.isInitialized()) jarvis.release()
        if (intercomDelegate.isInitialized()) intercom.release()
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
            addRiddenKm(moved / 1000.0, derived * 3.6)
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

        // Seta presa na rua (estilo Waze): o GPS do celular erra 10-40 m para o lado;
        // se estamos perto da rota, desenha a moto em cima da linha, apontando para a via.
        val snapLimit = if (loc.hasAccuracy()) loc.accuracy.coerceIn(25f, 45f) else 35f
        val sp = st.snapped
        if (sp != null && st.offRouteM <= snapLimit && !rerouting) {
            val pts = nav.route.points
            val i = st.progressIndex.coerceIn(0, pts.size - 2)
            val a = pts[i]
            val b = pts[i + 1]
            val k = cos(Math.toRadians(a.lat))
            val roadBearing = ((Math.toDegrees(atan2((b.lon - a.lon) * k, b.lat - a.lat)) + 360) % 360).toFloat()
            lastLocation = Location(loc).apply {
                latitude = sp.lat
                longitude = sp.lon
                if (a.distanceTo(b) > 1.0) bearing = roadBearing
            }
        }

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
                    val r = computeRoute(pts, m)
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
        copilotBriefing(r)
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
                val r = computeRoute(pts, m)
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
        if (speechLock.isLocked) {
            if (!urgent) {
                // o Jarvis está falando: o aviso espera ele terminar (em vez de falar por cima)
                viewModelScope.launch { speechLock.withLock { sayNow(text, false) } }
                return
            }
            // manobra agora: corta o Jarvis e fala a curva
            JarvisVoice.stopAll()
        }
        sayNow(text, urgent)
    }

    private fun sayNow(text: String, urgent: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val queueMode = if (!urgent && now - lastUrgentSpeechMs < 4000) {
            // Não corta uma instrução de curva recém-iniciada: coloca o alerta logo em seguida
            TextToSpeech.QUEUE_ADD
        } else {
            if (urgent) lastUrgentSpeechMs = now
            TextToSpeech.QUEUE_FLUSH
        }
        tts?.let { applyTtsRoute(it) }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = if (!hasInternet()) "Sem internet para buscar. Toque e segure no mapa para marcar o destino."
                else "A busca falhou (${e.message}). Tente de novo ou toque e segure no mapa."
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

    private var refreshChecked = false

    fun pollDownloads() {
        if (!refreshChecked) {
            refreshChecked = true
            val n = downloader.refreshStale()
            if (n > 0) message = "Atualizando $n bloco(s) do mapa offline no Wi-Fi (mais de 30 dias)."
        }
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

    // ---------- Jarvis (assistente de voz) ----------
    private val jarvisDelegate = lazy {
        Jarvis(
            ctx = getApplication(),
            scope = viewModelScope,
            tools = jarvisTools(),
            systemPrompt = ::jarvisPrompt,
            speak = ::speakAndWait,
            notify = { message = it },
            inCall = { intercomDelegate.isInitialized() && intercom.inCall },
            beforeListen = ::beforeJarvisListens,
            afterListen = ::afterJarvisListens,
        )
    }
    val jarvis: Jarvis by jarvisDelegate

    // ---------- Atualização instalada pelo próprio app (libera Acessibilidade/notificações) ----------
    /** progresso do download da atualização (0-100) ou null */
    var installProgress by mutableStateOf<Int?>(null); private set

    fun installUpdate(apkUrl: String?) {
        val ctx = getApplication<Application>()
        if (!SelfInstaller.ensureAllowed(ctx)) {
            message = "Permita \"Instalar apps desconhecidos\" para o MotoRota e toque de novo."
            return
        }
        if (installProgress != null) return
        viewModelScope.launch {
            installProgress = 0
            val url = apkUrl ?: withContext(Dispatchers.IO) { latestApkUrl() }
            if (url == null) {
                message = "Não achei a versão para baixar. Confira a internet."
                installProgress = null
                return@launch
            }
            message = SelfInstaller.install(ctx, url) { p -> viewModelScope.launch { installProgress = p } }
            installProgress = null
        }
    }

    private fun latestApkUrl(): String? {
        val r = Http.request("https://api.github.com/repos/${com.motorota.BuildConfig.GITHUB_REPO}/releases/latest")
        if (!r.ok) return null
        val assets = runCatching { JSONObject(r.text).optJSONArray("assets") }.getOrNull() ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) return a.optString("browser_download_url")
        }
        return null
    }

    // ---------- Estabelecimentos do mapa (TomTom, atualizados) ----------
    var pois by mutableStateOf<List<com.motorota.data.Pois.Poi>>(emptyList()); private set
    private var poiJob: Job? = null
    private var poiCenter: GeoPoint? = null

    fun loadPoisAround(center: GeoPoint, radiusM: Int) {
        val last = poiCenter
        if (last != null && last.distanceTo(center) < 350) return
        poiJob?.cancel()
        poiJob = viewModelScope.launch {
            delay(600) // espera o mapa parar de mexer
            val list = runCatching { com.motorota.data.Pois.around(center, radiusM) }.getOrDefault(emptyList())
            if (list.isNotEmpty() || pois.isEmpty()) {
                pois = list
                poiCenter = center
            }
        }
    }

    // ---------- Acesso aos aplicativos ----------
    val phone by lazy { PhoneActions(getApplication()) }
    /** permissões que o Jarvis precisa pedir agora (a tela mostra o pedido) */
    var permissionAsk by mutableStateOf<List<String>?>(null)
    /** ler em voz alta as mensagens que chegam (WhatsApp/Telegram/SMS) */
    // desligado por padrão: o Jarvis só fala das mensagens quando o piloto pergunta
    var readMessagesAloud by mutableStateOf(prefs.getBoolean("avisar_mensagens", false)); private set
    fun toggleReadMessages() {
        readMessagesAloud = !readMessagesAloud
        prefs.edit().putBoolean("avisar_mensagens", readMessagesAloud).apply()
    }

    private suspend fun phoneAction(block: () -> String): String = withContext(Dispatchers.Main) {
        try {
            block()
        } catch (e: PhoneActions.NeedPermission) {
            permissionAsk = e.permissions
            e.message ?: "Preciso de uma permissão."
        }
    }

    init {
        // mensagem nova: o Jarvis avisa (com o app aberto), sem ler o texto se estiver em chamada
        MessageListener.onNew = { m ->
            viewModelScope.launch {
                if (!readMessagesAloud) return@launch
                if (jarvisDelegate.isInitialized() && jarvis.busyNow) return@launch
                if (intercomDelegate.isInitialized() && intercom.busy) return@launch
                // só avisa de quem é (o conteúdo, só se o piloto pedir)
                speakAndWait("Mensagem de ${m.from} no ${m.app}.")
            }
        }
    }

    // ---------- Intercomunicador ----------
    private val intercomDelegate = lazy {
        Intercom(
            ctx = getApplication(),
            scope = viewModelScope,
            speak = ::speakAndWait,
            askYesNo = { q -> jarvis.askYesNo(q) },
            notify = { message = it },
            onAudioStart = { jarvis.pauseForCall() },
            onAudioEnd = { jarvis.resumeAfterCall() },
            wakeFeeder = { jarvis.feederForCall() },
        )
    }
    val intercom: Intercom by lazy {
        intercomDelegate.value.also { ic ->
            ic.onRecado = { r -> announceRecado(r) }
        }
    }

    private val recadoQueue = kotlinx.coroutines.sync.Mutex()

    /** Recado de outro piloto: o Jarvis fala na hora (espera terminar o que estiver falando). */
    private fun announceRecado(r: com.motorota.intercom.Recado) {
        message = if (r.notice) "👥 ${r.fromName} criou o grupo ${r.group} com você"
        else "💬 ${r.fromName}${r.group?.let { " ($it)" } ?: ""}: ${r.text}"
        viewModelScope.launch {
            recadoQueue.withLock {
                var tries = 0
                while (jarvisDelegate.isInitialized() && jarvis.busyNow && tries < 40) { delay(1500); tries++ }
                val me = riderName.ifBlank { intercomDelegate.value.myName }.split(' ').firstOrNull().orEmpty()
                val lead = if (me.isNotBlank()) "$me, " else ""
                if (r.notice) {
                    speakAndWait("${lead}o ${r.fromName} criou o grupo ${r.group} e te colocou nele. Pode mandar recado para o grupo quando quiser.")
                } else {
                    val where = r.group?.let { "no grupo $it, " } ?: ""
                    speakAndWait("$lead${where}recado do ${r.fromName}: ${r.text}")
                }
            }
        }
    }
    private var jarvisHeldRoute = false

    /** Jarvis vai ouvir: solta o microfone da chamada e, com capacete conectado, usa o microfone dele. */
    private suspend fun beforeJarvisListens() {
        val ic = intercom
        if (ic.inCall) {
            ic.pauseMic()
            delay(250)
        }
        if (!jarvisHeldRoute && ic.route.helmetConnected()) {
            ic.route.acquire()
            jarvisHeldRoute = true
            if (!ic.inCall) delay(700) // o Bluetooth leva um instante para abrir o microfone
        }
    }

    private fun afterJarvisListens() {
        val ic = intercom
        if (jarvisHeldRoute) {
            ic.route.release()
            jarvisHeldRoute = false
        }
        if (ic.inCall) ic.resumeMic()
    }

    /** Em chamada, a voz do Jarvis vai pelo canal da ligação (capacete); fora dela, como mídia. */
    private fun applyTtsRoute(t: TextToSpeech) {
        val voice = intercomDelegate.isInitialized() && intercom.inCall
        runCatching {
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (voice) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        }
    }

    /** Fala e só volta quando terminar (o Jarvis espera para voltar a ouvir). Ignora o "mudo" dos avisos. */
    /** voz do Jarvis: nome de uma voz do Google (Gemini) ou "celular" (voz do próprio Android) */
    // padrão: voz offline Piper "faber" (grátis e sem limite). Quem já tinha escolhido outra, mantém.
    // padrão: voz do Google (Charon); quando a cota grátis acaba, entra a voz offline ajustada (não a robótica)
    var jarvisVoice by mutableStateOf(prefs.getString("jarvis_voz3", null) ?: "Charon"); private set
    /** progresso do download da voz offline (0-100), ou null se não está baixando */
    var voiceDownload by mutableStateOf<Int?>(null); private set

    fun setJarvisVoiceName(v: String) {
        jarvisVoice = v
        prefs.edit().putString("jarvis_voz3", v).apply()
        if (v.startsWith("piper:")) ensurePiper(v.removePrefix("piper:"))
    }

    /** Baixa a voz offline se ainda não tiver (uma vez só, ~65 MB). */
    fun ensurePiper(id: String) {
        val ctx = getApplication<Application>()
        if (PiperVoice.ready(ctx, id)) {
            viewModelScope.launch { PiperVoice.warmUp(ctx, id) }
            return
        }
        if (PiperVoice.downloading != null) return
        viewModelScope.launch {
            message = "Baixando a voz do Jarvis (65 MB, só desta vez). Use Wi-Fi se puder."
            val watcher = launch {
                while (true) {
                    voiceDownload = PiperVoice.progress
                    delay(500)
                }
            }
            try {
                PiperVoice.download(ctx, id)
                PiperVoice.warmUp(ctx, id)
                message = "Voz do Jarvis pronta!"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Não deu para baixar a voz agora (${e.message}). Tento de novo depois."
            } finally {
                watcher.cancel()
                voiceDownload = null
            }
        }
    }

    private var voiceQuotaWarned = false

    /** Copiloto: ao sair, avisa se o combustível não dá para a viagem ou se ela é longa. */
    private fun copilotBriefing(r: RouteResult) {
        val fuel = fuelLeftKm
        val km = r.distanceM / 1000
        val tips = ArrayList<String>()
        if (fuel != null && fuel - 25 < km) {
            tips.add("Sua autonomia é de uns $fuel quilômetros e a rota tem $km. Vai precisar abastecer no caminho; me pergunte onde quando quiser.")
        }
        if (r.timeS > 2 * 3600) tips.add("Viagem de ${formatDuration(r.timeS)}. Bom fazer uma pausa a cada duas horas.")
        if (tips.isEmpty()) return
        viewModelScope.launch {
            delay(3500) // depois do "Vamos lá"
            if (navigating) speakAndWait(tips.joinToString(" "))
        }
    }

    /**
     * Fala com a voz offline frase por frase: enquanto uma frase toca, a próxima já está sendo gerada
     * (começa a falar mais rápido) e há uma pausa natural entre elas. Um pouco mais lenta e mais grave.
     */
    private suspend fun speakPiper(id: String, text: String, inCall: Boolean): Boolean {
        val ctx = getApplication<Application>()
        if (!PiperVoice.ready(ctx, id)) {
            ensurePiper(id)
            return false
        }
        val parts = text.split(Regex("(?<=[.!?…;:])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        tts?.stop()
        lastUrgentSpeechMs = SystemClock.elapsedRealtime()
        return kotlinx.coroutines.coroutineScope {
            var next = async { PiperVoice.synth(ctx, id, parts[0], speed = 0.9f) }
            var spoke = false
            for (i in parts.indices) {
                val audio = next.await()
                if (i + 1 < parts.size) {
                    val t = parts[i + 1]
                    next = async { PiperVoice.synth(ctx, id, t, speed = 0.9f) }
                }
                if (audio != null) {
                    runCatching { JarvisVoice.play(audio, inCall, rateFactor = 0.93f) }
                    spoke = true
                    if (i + 1 < parts.size) delay(260)
                }
            }
            spoke
        }
    }

    /** Uma fala por vez: resposta do Jarvis, recado, aviso de mensagem e dica do copiloto entram em fila. */
    private val speechLock = kotlinx.coroutines.sync.Mutex()
    val speakingNow get() = speechLock.isLocked

    suspend fun speakAndWait(text: String) {
        if (text.isBlank()) return
        speechLock.withLock { speakNow(text) }
    }

    private suspend fun speakNow(text: String) {
        val inCallNow = intercomDelegate.isInitialized() && intercom.inCall
        // Voz offline (Piper): grátis, sem limite e sem internet
        if (jarvisVoice.startsWith("piper:")) {
            if (speakPiper(jarvisVoice.removePrefix("piper:"), text, inCallNow)) return
        }
        // Voz natural do Google; se não der (sem internet, limite), cai na voz do celular
        if (!jarvisVoice.startsWith("piper:") && jarvisVoice != "celular" && Keys.gemini.isNotBlank()) {
            val audio = runCatching { JarvisVoice.synth(Keys.gemini, text, jarvisVoice) }.getOrNull()
            if (audio == null && JarvisVoice.quotaHit && !voiceQuotaWarned) {
                voiceQuotaWarned = true
                message = "A voz do Google atingiu o limite grátis por agora. O Jarvis usa a voz do celular até liberar."
            }
            if (audio != null) {
                tts?.stop()
                lastUrgentSpeechMs = SystemClock.elapsedRealtime()
                runCatching { JarvisVoice.play(audio, inCallNow) }
                return
            }
            // sem a voz do Google (limite/sem internet): voz offline ajustada, que é bem melhor que a do celular
            if (speakPiper("faber", text, inCallNow)) return
        }
        val t = tts ?: return
        if (!ttsReady || text.isBlank()) return
        val id = "jarvis-${++speechSeq}"
        val done = CompletableDeferred<Unit>()
        speechDone[id] = done
        lastUrgentSpeechMs = SystemClock.elapsedRealtime()
        applyTtsRoute(t)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        withTimeoutOrNull(4000L + text.length * 110L) { done.await() }
        speechDone.remove(id)
    }

    private fun jarvisPrompt(): String {
        val now = java.time.LocalDateTime.now()
        val quando = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy, HH:mm", Locale("pt", "BR")))
        val onde = myLocation?.let { String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) } ?: "desconhecida (sem GPS)"
        val rota = when {
            navigating -> "navegando para ${destination?.name ?: "o destino"}"
            destination != null -> "rota planejada para ${destination?.name}, ainda não iniciada"
            else -> "sem rota no momento"
        }
        return """
            Você é o Jarvis, o copiloto de voz do MotoRota, um app de navegação para moto. Quem fala com você é o piloto, de capacete, pilotando.
            Pense como um motociclista experiente de estrada: além de responder, antecipe o que importa na viagem (combustível, descanso, clima, pôr do sol, trechos perigosos, radares) e dê a recomendação prática, como um parceiro de viagem faria. Ex.: "Sua autonomia dá uns 100 km e faltam 200; eu abasteceria no posto daqui a 60 km, descansava 15 minutos e seguia."
            Para combustível e paradas use a função planejar_abastecimento_e_paradas; nunca chute autonomia.
            Regras:
            - Responda sempre em português do Brasil, como uma pessoa conversando: natural, calorosa e bem explicada, em 2 a 4 frases (é falado no capacete, então nada de textão). Use vírgulas e pontos para a fala ter pausas.
            - Nada de markdown, listas, asteriscos ou emojis. Fale números de um jeito natural (ex.: "dois radares, o primeiro a 800 metros").
            - Para qualquer coisa do app (traçar rota, parada, radares, quanto falta, chegada, trânsito, clima, procurar lugares, onde estou, parar navegação, voz dos avisos) USE AS FUNÇÕES. Nunca invente radares, distâncias ou tempos: use só o que as funções devolverem.
            - "Programa a rota pra X", "me leva pra X", "vamos pra X" = navegar_para.
            - Se o destino for ambíguo, escolha o mais provável perto do piloto; só pergunte se for impossível decidir.
            - Ao confirmar uma rota, diga o bairro/cidade e a distância do destino escolhido (vêm no resultado da função), para o piloto perceber se for o lugar errado. Se ele disser que está errado, chame navegar_para de novo com o nome mais completo (ex.: "Xerém, Duque de Caxias").
            - Fora isso, converse normalmente sobre qualquer assunto, como uma IA comum, com bom humor e sem enrolar.
            - Pense na segurança: nunca incentive o piloto a mexer no celular enquanto pilota.
            - Celular: você pode abrir aplicativos, ligar para contatos, mandar WhatsApp, ler mensagens novas e controlar música e volume pelas funções. Contas e cálculos você faz sozinho, de cabeça. "Liga pra X" = ligação telefônica comum (ligar_para_contato); "chama/entra em contato com X" = intercomunicador do app (chamar_piloto) se X for um amigo do intercomunicador. "Manda mensagem/recado/avisa pro X" = mandar_recado se X for amigo ou grupo do intercomunicador (o Jarvis dele fala em voz alta); se não for, enviar_whatsapp. Se o piloto chamar alguém pelo parentesco ("meu pai"), use o amigo que ele já identificou na conversa ou pergunte qual é.
            - Paradas no caminho ("quero parar num posto no meio do caminho"): use adicionar_parada com lugar e onde. Nunca escolha lugar que obrigue a voltar ou desviar muito; diga em que km fica.
            - Mensagens: se perguntarem "chegou mensagem?" ou "de quem é a mensagem?", diga só de quem chegou; leia o conteúdo apenas se o piloto pedir ("lê a da minha mãe").
            - Mensagens marcadas [seguimento] foram captadas pelo microfone sem o piloto dizer "Jarvis", logo depois de você responder. O piloto costuma estar conversando com a garupa ou com outro piloto pelo intercomunicador. Se a frase não for claramente um pedido ou uma continuação da conversa com você, responda exatamente: [IGNORAR] (nada mais). Na dúvida, [IGNORAR].
            - Mensagens marcadas [chamado] vieram logo depois do detector ouvir algo parecido com "Jarvis", e ele às vezes se engana com conversa ou barulho. Se a frase não fizer sentido como pedido, pergunta ou papo com você (pedaço de conversa com outra pessoa, frase solta, ruído), responda exatamente: [IGNORAR]. Nunca responda "não entendi" nem peça para repetir.
            - Intercomunicador: "entra em contato com X", "chama o X", "liga pro X" = chamar_piloto. Durante a chamada, "desliga"/"encerra" = encerrar_chamada. Se estiver tocando, "atende"/"recusa" = atender_chamada / recusar_chamada.
            Piloto: ${riderName.ifBlank { "não informado" }}. Moto: ${bike?.let { b -> "${b.name}, tanque de ${b.tankL} litros, consumo estimado ${"%.1f".format(b.kmL)} km/L (margem ±${b.marginPct}%), autonomia atual cerca de ${b.leftKm} km" } ?: "não configurada"}.
            Situação agora: $quando. Localização: $onde. Velocidade: $speedKmh km/h. Estado: $rota.
            Intercomunicador: ${intercomStatus()}
        """.trimIndent()
    }

    private fun intercomStatus(): String {
        val ic = intercom
        val amigos = ic.friends.joinToString(", ") { it.name }.ifBlank { "nenhum amigo cadastrado ainda" } +
            (if (ic.groups.isNotEmpty()) "; grupos: " + ic.groups.joinToString(", ") { g -> "${g.name} (${g.members.joinToString(", ") { it.name }})" } else "") +
            (if (ic.recent.isNotEmpty()) "; últimos recados recebidos: " + ic.recent.takeLast(5).joinToString(" | ") { "${it.fromName}${it.group?.let { g -> " no $g" } ?: ""}: ${it.text}" } else "")
        val agora = when (ic.state) {
            CallState.IDLE -> "sem chamada"
            CallState.CALLING -> "chamando ${ic.peerName}"
            CallState.RINGING -> "${ic.peerName} está chamando o piloto agora"
            CallState.IN_CALL -> "em chamada com ${ic.peerName}"
        }
        val nome = ic.myName.ifBlank { "(o piloto ainda não colocou o nome em Ajustes)" }
        return "nome do piloto: $nome; amigos: $amigos; $agora."
    }

    private fun jarvisTools(): List<Tool> = listOf(
        Tool(
            "abrir_aplicativo",
            "Abre (traz para a tela) um aplicativo instalado pelo nome. Use SÓ quando o piloto pedir para ABRIR um app; " +
                "para música use a função musica, que toca em segundo plano sem tirar o mapa da tela.",
            mapOf("nome" to ("STRING" to "nome do aplicativo")),
            listOf("nome"),
        ) { a -> phoneAction { phone.openApp(a.optString("nome")) } },
        Tool(
            "ligar_para_contato",
            "Faz uma ligação telefônica normal para um contato da agenda do celular.",
            mapOf("contato" to ("STRING" to "nome do contato, como o piloto falou")),
            listOf("contato"),
        ) { a -> phoneAction { phone.call(a.optString("contato")) } },
        Tool(
            "enviar_whatsapp",
            "Manda uma mensagem de WhatsApp para um contato da agenda. Escreva a mensagem do jeito que o piloto pediu, em primeira pessoa.",
            mapOf(
                "contato" to ("STRING" to "nome do contato"),
                "mensagem" to ("STRING" to "texto da mensagem"),
            ),
            listOf("contato", "mensagem"),
        ) { a -> phoneAction { phone.whatsapp(a.optString("contato"), a.optString("mensagem")) } },
        Tool(
            "mensagens_recebidas",
            "Mensagens novas que chegaram (WhatsApp, Telegram, SMS). Por padrão devolve só DE QUEM são; " +
                "use ler_conteudo=true quando o piloto pedir para ler o que dizem (pode filtrar por contato).",
            mapOf(
                "ler_conteudo" to ("BOOLEAN" to "true para ler o texto das mensagens"),
                "contato" to ("STRING" to "só as mensagens desta pessoa (opcional)"),
            ),
        ) { a ->
            phoneAction {
                phone.readMessages(a.optBoolean("ler_conteudo", false), a.optString("contato").takeIf { it.isNotBlank() })
            }
        },
        Tool(
            "musica",
            "Controla a música em segundo plano, sem abrir o app (Spotify ou outro): tocar, pausar, próxima, anterior; " +
                "ou tocar uma música/artista/playlist pelo nome.",
            mapOf(
                "acao" to ("STRING" to "tocar, pausar, proxima ou anterior"),
                "busca" to ("STRING" to "música, artista ou playlist para tocar (opcional)"),
            ),
            listOf("acao"),
        ) { a -> phoneAction { phone.music(a.optString("acao"), a.optString("busca").takeIf { it.isNotBlank() }) } },
        Tool(
            "volume",
            "Muda o volume da música/voz: aumentar, diminuir, maximo, mudo, ou um valor exato em porcentagem (ex.: volume em 30%).",
            mapOf(
                "acao" to ("STRING" to "aumentar, diminuir, maximo, mudo ou definir"),
                "porcentagem" to ("NUMBER" to "0 a 100, quando o piloto disser um valor (opcional)"),
            ),
            listOf("acao"),
        ) { a ->
            val pct = if (a.has("porcentagem")) a.optDouble("porcentagem", -1.0).toInt().takeIf { it in 0..100 } else null
            phoneAction { phone.volume(a.optString("acao"), pct) }
        },
        Tool(
            "chamar_piloto",
            "Liga pelo intercomunicador do app para um amigo da lista (o celular dele toca e o Jarvis dele pergunta se pode atender).",
            mapOf("nome" to ("STRING" to "nome do amigo, como o piloto falou")),
            listOf("nome"),
        ) { a ->
            withContext(Dispatchers.Main) {
                val ic = intercom
                val f = ic.findFriend(a.optString("nome"))
                if (f == null) {
                    val lista = ic.friends.joinToString(", ") { it.name }
                    if (lista.isBlank()) "Você ainda não tem amigos no intercomunicador. Adicione pelo código deles em Ajustes."
                    else "Não achei \"${a.optString("nome")}\" nos amigos. Os amigos são: $lista."
                } else {
                    ic.call(f)
                }
            }
        },
        Tool(
            "mandar_recado",
            "Manda um recado pelo MotoRota para um amigo ou grupo do intercomunicador (o Jarvis dele fala o recado em voz alta; " +
                "se faltar internet, guarda e envia quando voltar). Use para amigos/grupos do app; WhatsApp só se o piloto falar WhatsApp " +
                "ou a pessoa não estiver nos amigos. Escreva o recado como o piloto falou, em primeira pessoa.",
            mapOf(
                "destino" to ("STRING" to "nome do amigo ou do grupo"),
                "recado" to ("STRING" to "texto do recado"),
            ),
            listOf("destino", "recado"),
        ) { a ->
            withContext(Dispatchers.Main) {
                val ic = intercom
                val d = a.optString("destino")
                val g = ic.findGroup(d)
                val f = if (g == null) ic.findFriend(d) else null
                if (g == null && f == null) {
                    "Não achei \"$d\" nos amigos nem nos grupos do MotoRota. Amigos: ${ic.friends.joinToString(", ") { it.name }.ifBlank { "nenhum" }}" +
                        "; grupos: ${ic.groups.joinToString(", ") { it.name }.ifBlank { "nenhum" }}."
                } else ic.sendRecado(f, g, a.optString("recado"))
            }
        },
        Tool("encerrar_chamada", "Desliga ou cancela a chamada do intercomunicador.") { _ ->
            withContext(Dispatchers.Main) {
                if (intercom.busy) { intercom.hangUp(); "Chamada encerrada." } else "Não há chamada agora."
            }
        },
        Tool("atender_chamada", "Atende a chamada do intercomunicador que está tocando.") { _ ->
            withContext(Dispatchers.Main) {
                if (intercom.state == CallState.RINGING) { intercom.accept(); "Atendendo." } else "Não tem chamada tocando."
            }
        },
        Tool("recusar_chamada", "Recusa a chamada do intercomunicador que está tocando.") { _ ->
            withContext(Dispatchers.Main) {
                if (intercom.state == CallState.RINGING) { intercom.reject(); "Chamada recusada." } else "Não tem chamada tocando."
            }
        },
        Tool(
            "navegar_para",
            "Traça a rota até um lugar e já começa a navegação guiada por voz.",
            mapOf("destino" to ("STRING" to "lugar, endereço, bairro, cidade ou estabelecimento, como o piloto falou")),
            listOf("destino"),
        ) { a -> jarvisNavigate(a.optString("destino")) },
        Tool(
            "adicionar_parada",
            "Adiciona uma parada NO CAMINHO da rota atual. Para tipos de lugar (posto, restaurante, farmácia...) " +
                "procura ao longo da rota, sem desvio grande. Recalcula a rota.",
            mapOf(
                "lugar" to ("STRING" to "o que procurar (ex.: posto, restaurante) ou um lugar específico"),
                "onde" to ("STRING" to "em que parte do caminho: proximo, meio do caminho, perto do destino, ou 'km 40'"),
            ),
            listOf("lugar"),
        ) { a -> jarvisAddStop(a.optString("lugar"), a.optString("onde").takeIf { it.isNotBlank() }) },
        Tool(
            "buscar_na_rota",
            "Lista postos, restaurantes, farmácias etc. que ficam no caminho da rota (à frente), com em que km estão e quanto saem da rota.",
            mapOf("o_que" to ("STRING" to "tipo de lugar: posto, restaurante, farmácia, padaria, borracharia, hotel...")),
            listOf("o_que"),
        ) { a -> jarvisSearchAlong(a.optString("o_que")) },
        Tool(
            "radares_a_frente",
            "Conta os radares de velocidade no caminho da rota traçada (navegando: daqui até o destino) e diz a distância e o limite de cada um. Sem rota, conta os que estão em volta.",
            mapOf("distancia_km" to ("NUMBER" to "até quantos km à frente olhar; não envie para considerar a rota inteira")),
        ) { a -> jarvisRadars(a) },
        Tool(
            "marcar_radar",
            "Salva um radar fixo na posição atual do piloto (para radares que faltam no mapa). Use quando ele disser \"marca um radar aqui\".",
            mapOf("limite_kmh" to ("NUMBER" to "limite de velocidade do radar, se o piloto disser")),
        ) { a -> jarvisMarkRadar(a) },
        Tool(
            "planejar_abastecimento_e_paradas",
            "Copiloto: cruza a autonomia do tanque com o que falta da rota e o tempo de viagem; diz se precisa abastecer, em que ponto da rota e quais postos há perto desse ponto, e quando descansar.",
        ) { _ -> jarvisFuelPlan() },
        Tool(
            "situacao_da_viagem",
            "Quanto falta (distância e tempo), hora prevista de chegada, próxima manobra, velocidade, limite da via, próximos alertas, trânsito, clima e autonomia do tanque.",
        ) { _ -> jarvisStatus() },
        Tool(
            "buscar_lugares",
            "Procura lugares (ex.: posto de gasolina, farmácia, hotel, borracharia, um endereço) perto do piloto e diz a distância de cada um. Não traça rota.",
            mapOf("consulta" to ("STRING" to "o que procurar")),
            listOf("consulta"),
        ) { a -> jarvisSearch(a.optString("consulta")) },
        Tool(
            "onde_estou",
            "Diz a rua, o bairro e a cidade onde o piloto está agora.",
        ) { _ -> jarvisWhereAmI() },
        Tool(
            "parar_navegacao",
            "Encerra a navegação atual.",
        ) { _ ->
            withContext(Dispatchers.Main) {
                if (navigating) { stopNavigation(); "Navegação encerrada." } else "Não havia navegação ativa."
            }
        },
        Tool(
            "avisos_de_voz",
            "Liga ou desliga os avisos falados de manobra e radar (o Jarvis continua respondendo).",
            mapOf("ligar" to ("BOOLEAN" to "true para ligar, false para desligar")),
            listOf("ligar"),
        ) { a ->
            withContext(Dispatchers.Main) {
                val want = a.optBoolean("ligar", true)
                if (voiceOn != want) toggleVoice()
                if (want) "Avisos de voz ligados." else "Avisos de voz desligados."
            }
        },
    )

    private suspend fun jarvisNavigate(dest: String): String {
        if (dest.isBlank()) return "Faltou dizer o destino."
        val here = withContext(Dispatchers.Main) { myLocation }
        val found = Geocoder.search(dest, here)
        val p = found.firstOrNull() ?: return "Não encontrei \"$dest\" no mapa."
        if (!Geocoder.matches(p, dest)) {
            Http.report("jarvis-destino", "pediu \"$dest\" -> sem nome igual, perguntou: " + found.take(4).joinToString(" | ") { "${it.name} (${it.detail})" })
            return "Não achei exatamente \"$dest\". Os mais parecidos: " +
                found.take(3).joinToString("; ") { "${it.name} (${it.detail})" } +
                ". Pergunte ao piloto qual é, ou peça a cidade mais próxima do lugar."
        }
        Http.report("jarvis-destino", "pediu \"$dest\" -> " + found.take(4).joinToString(" | ") { "${it.name} (${it.detail})" })
        withContext(Dispatchers.Main) {
            stops = emptyList()
            origin = null
            pendingStart = true
            goTo(Waypoint(p.point, p.name))
        }
        repeat(150) { // viagem longa pode levar até ~45 s para calcular
            delay(300)
            val done = withContext(Dispatchers.Main) {
                val r = currentRoute
                val err = routeErrors[mode]
                when {
                    r != null -> "Destino: ${p.name} (${p.detail}). Rota de ${formatDistance(r.distanceM.toDouble())}, " +
                        "cerca de ${formatDuration(r.timeS)}. Navegação iniciada."
                    err != null -> "Achei ${p.name} (${p.detail}), mas não consegui traçar a rota: $err"
                    myLocation == null -> "Achei ${p.name}, mas estou sem GPS para traçar a rota."
                    else -> null
                }
            }
            if (done != null) return done
        }
        return "Destino ${p.name} definido; a rota ainda está sendo calculada."
    }

    /** Rota atual e onde a moto está nela (metros desde o início). */
    private suspend fun routeNow(): Pair<RouteResult, Double>? = withContext(Dispatchers.Main) {
        val r = currentRoute ?: navigator?.route ?: return@withContext null
        r to (if (navigating) navState?.alongM ?: 0.0 else 0.0)
    }

    private suspend fun jarvisSearchAlong(what: String): String {
        val (r, from) = routeNow() ?: return "Não tem rota traçada. Me diga para onde vamos primeiro."
        val list = RouteSearch.alongRoute(r, from, what)
        Http.report("busca-rota", "\"$what\" na rota de ${formatDistance(r.distanceM.toDouble())}: achou ${list.size}")
        if (list.isEmpty()) return "Não achei $what no caminho."
        return "${list.size} no caminho: " + list.take(6).joinToString("; ") {
            "${it.place.name} a ${formatDistance(it.alongM - from)} daqui" +
                (if (it.offM > 150) ", sai ${formatDistance(it.offM)} da rota" else ", na beira da rota") +
                (if (it.place.detail.isNotBlank()) " (${it.place.detail})" else "")
        } + "."
    }

    private suspend fun jarvisAddStop(place: String, where: String? = null): String {
        if (place.isBlank()) return "Faltou dizer o lugar da parada."
        val hasDest = withContext(Dispatchers.Main) { destination != null }
        if (!hasDest) return jarvisNavigate(place)
        // tipo de lugar (posto, restaurante...): procura AO LONGO da rota, não perto de onde estou
        val rn = routeNow()
        if (rn != null && (RouteSearch.isCategory(place) || where != null)) {
            val (r, from) = rn
            val found = RouteSearch.alongRoute(r, from, place)
            val best = RouteSearch.pick(found, from, r.cumDist.last(), where)
            Http.report(
                "busca-rota",
                "parada \"$place\" onde=${where ?: "-"}: ${found.size} achados; escolheu ${best?.place?.name} km ${best?.let { (it.alongM - from) / 1000 }}",
            )
            if (best != null) {
                withContext(Dispatchers.Main) {
                    if (navigating) pendingStart = true
                    addStop(Waypoint(best.place.point, best.place.name))
                }
                return "Parada em ${best.place.name}, a ${formatDistance(best.alongM - from)} daqui no caminho" +
                    (if (best.offM > 150) ", saindo uns ${formatDistance(best.offM)} da rota" else ", na beira da estrada") +
                    (if (best.place.detail.isNotBlank()) " (${best.place.detail})" else "") +
                    ". Rota recalculada." + if (found.size > 1) " Tem mais ${found.size - 1} opções no caminho, se preferir outra." else ""
            }
            if (RouteSearch.isCategory(place)) return "Não achei $place no caminho da rota."
        }
        val here = withContext(Dispatchers.Main) { myLocation }
        val p = Geocoder.search(place, here).firstOrNull() ?: return "Não encontrei \"$place\"."
        withContext(Dispatchers.Main) {
            if (navigating) pendingStart = true
            addStop(Waypoint(p.point, p.name))
        }
        return "Parada em ${p.name} (${p.detail}) adicionada. A rota está sendo recalculada."
    }

    private suspend fun jarvisRadars(a: JSONObject): String {
        val km = if (a.has("distancia_km")) a.optDouble("distancia_km", -1.0) else -1.0
        val route = withContext(Dispatchers.Main) { currentRoute ?: navigator?.route }
        val fromM = withContext(Dispatchers.Main) { if (navigating) navState?.alongM ?: 0.0 else 0.0 }
        if (route != null && route.points.size > 1) {
            // calcula na hora, em cima da rota atual (não depende da lista de alertas já estar pronta)
            val all = withContext(Dispatchers.Default) { alertsOn(route) }
            val maxM = if (km > 0) km * 1000 else Double.MAX_VALUE
            val ahead = all.filter { it.isRadar && it.posM > fromM && it.posM - fromM <= maxM }.sortedBy { it.posM }
            val faixa = when {
                km > 0 -> "nos próximos ${formatDistance(km * 1000)} da rota"
                fromM > 0 -> "daqui até o destino"
                else -> "no caminho inteiro"
            }
            Http.report("jarvis-radares", "rota ${formatDistance(route.distanceM.toDouble())}, achou ${ahead.size} radares $faixa")
            return if (ahead.isEmpty()) {
                "Nenhum radar cadastrado $faixa. Se você conhece algum, quando passar por ele diga \"Jarvis, marca um radar aqui\"."
            } else {
                "${ahead.size} radar(es) $faixa: " + ahead.take(8).joinToString("; ") {
                    "a ${formatDistance(it.posM - fromM)}" + if (it.limitKmh > 0) ", limite ${it.limitKmh}" else ""
                } + "."
            }
        }
        return withContext(Dispatchers.Main) {
            val me = myLocation ?: return@withContext "Estou sem GPS agora."
            val raio = (if (km > 0) km else 5.0) * 1000
            val near = radarStore.all
                .map { it to it.point.distanceTo(me) }
                .filter { it.second <= raio }
                .sortedBy { it.second }
            val faixa = "num raio de ${formatDistance(raio)} (sem rota traçada, então não sei quais estão no seu caminho)"
            if (near.isEmpty()) "Nenhum radar $faixa."
            else "${near.size} radar(es) $faixa. Os mais perto: " + near.take(4).joinToString("; ") { (r, d) ->
                "a ${formatDistance(d)}" + if (r.limitKmh > 0) ", limite ${r.limitKmh}" else ""
            } + "."
        }
    }

    /** Ponto da rota a `m` metros do início. */
    private fun pointAt(r: RouteResult, m: Double): GeoPoint {
        val i = r.cumDist.indexOfFirst { it >= m }.let { if (it < 0) r.points.size - 1 else it }
        return r.points[i.coerceIn(0, r.points.size - 1)]
    }

    private suspend fun jarvisFuelPlan(): String {
        data class Snap(val r: RouteResult?, val from: Double, val remM: Double, val remS: Int, val fuel: Int?, val tank: Int, val elapsedS: Long)
        val snap = withContext(Dispatchers.Main) {
            val r = currentRoute ?: navigator?.route
            val st = navState
            Snap(
                r,
                if (navigating && st != null) st.alongM else 0.0,
                if (navigating && st != null) st.remainingM else (r?.distanceM?.toDouble() ?: 0.0),
                if (navigating && st != null) st.remainingS else (r?.timeS ?: 0),
                fuelLeftKm, bike?.fullRangeKm ?: tankKm, if (navigating) tripElapsedS else 0L,
            )
        }
        val sb = StringBuilder()
        val remKm = snap.remM / 1000
        if (snap.r == null) sb.append("Sem rota traçada. ")
        else sb.append("Faltam ${remKm.toInt()} km, cerca de ${formatDuration(snap.remS)}. ")
        if (snap.fuel == null) {
            sb.append("A autonomia do tanque não está configurada (Ajustes, Autonomia do tanque), então não sei quanto combustível resta. ")
        } else {
            sb.append("Autonomia estimada: ${snap.fuel} km (tanque cheio faz ${snap.tank} km). ")
            val margin = 25 // km de reserva que um piloto prudente mantém
            if (snap.r != null && snap.fuel - margin < remKm) {
                val stopKm = (snap.fuel - margin).coerceAtLeast(5)
                val atM = snap.from + stopKm * 1000.0
                sb.append("Precisa abastecer no caminho: o ideal é por volta de $stopKm km daqui, antes de entrar na reserva. ")
                // postos NO CAMINHO, antes do ponto limite (ninguém quer voltar ou desviar muito)
                val postos = runCatching { RouteSearch.alongRoute(snap.r, snap.from, "posto de gasolina") }
                    .getOrDefault(emptyList())
                    .filter { it.alongM <= atM + 3000 }
                    .sortedByDescending { it.alongM }
                    .take(3)
                if (postos.isNotEmpty()) {
                    sb.append("Postos no caminho antes desse ponto: " + postos.joinToString("; ") {
                        "${it.place.name} a ${formatDistance(it.alongM - snap.from)} daqui" +
                            if (it.offM > 150) " (sai ${formatDistance(it.offM)} da rota)" else ""
                    } + ". ")
                } else sb.append("Não achei posto cadastrado no caminho antes desse ponto; abasteça no primeiro que aparecer. ")
            } else if (snap.r != null) {
                sb.append("Dá para chegar sem abastecer, sobrando uns ${(snap.fuel - remKm).toInt()} km. ")
            }
        }
        // descanso: a cada ~2 h de moto
        val totalS = snap.elapsedS + snap.remS
        if (totalS > 2 * 3600) {
            val nextRestS = (2 * 3600 - snap.elapsedS % (2 * 3600)).coerceAtLeast(0)
            sb.append("Viagem longa: recomendo parar uns 15 minutos a cada 2 horas; a próxima parada seria em uns ${formatDuration(nextRestS.toInt())}. ")
        }
        routeWeather?.rainText?.let { sb.append("Clima: $it. ") }
        routeWeather?.sunsetText?.let { sb.append("$it. ") }
        return sb.toString()
    }

    private suspend fun jarvisMarkRadar(a: JSONObject): String = withContext(Dispatchers.Main) {
        val me = myLocation ?: return@withContext "Estou sem GPS agora, não consigo marcar."
        val lim = a.optInt("limite_kmh", 0).coerceIn(0, 150)
        val added = withContext(Dispatchers.IO) { radarStore.addUser(me, lim) }
        if (!added) return@withContext "Já tenho um radar marcado bem aqui."
        radarsVersion++
        currentRoute?.let { if (navigating) refreshAlertsAsync(it) }
        "Radar marcado aqui" + (if (lim > 0) ", limite $lim" else "") + ". Vou avisar sempre que passar por ele."
    }

    private suspend fun jarvisStatus(): String = withContext(Dispatchers.Main) {
        val sb = StringBuilder()
        sb.append("Velocidade atual: $speedKmh km/h. ")
        currentLimit?.let { sb.append("Limite da via: $it km/h. ") }
        val st = navState
        if (navigating && st != null) {
            val eta = LocalTime.now().plusSeconds(st.remainingS.toLong())
            sb.append(
                "Indo para ${destination?.name ?: "o destino"}. Faltam ${formatDistance(st.remainingM)}, " +
                    "cerca de ${formatDuration(st.remainingS)}, chegada por volta de ${eta.hour}:${String.format(Locale.US, "%02d", eta.minute)}. ",
            )
            st.nextHint?.let { sb.append("Próxima manobra: ${Instructions.text(it)} em ${formatDistance(st.distToHintM)}. ") }
            val next = alongAlerts.filter { it.posM > st.alongM }.sortedBy { it.posM }.take(3)
            if (next.isNotEmpty()) {
                sb.append("Próximos alertas: " + next.joinToString("; ") { "${it.title} a ${formatDistance(it.posM - st.alongM)}" } + ". ")
            }
            if (stops.isNotEmpty()) sb.append("Paradas que faltam: ${stops.joinToString(", ") { it.name }}. ")
        } else if (destination != null) {
            val r = currentRoute
            if (r != null) sb.append("Rota planejada para ${destination?.name}: ${formatDistance(r.distanceM.toDouble())}, ${formatDuration(r.timeS)}, ainda não iniciada. ")
            else sb.append("Destino ${destination?.name} escolhido, rota ainda sendo calculada. ")
        } else {
            sb.append("Nenhuma rota ativa. ")
        }
        if (liveIncidents.isNotEmpty()) {
            sb.append("Ocorrências de trânsito na rota: ${liveIncidents.take(3).joinToString("; ") { it.title }}. ")
        }
        trafficStatus?.let { sb.append("Trânsito: $it. ") }
        routeWeather?.rainText?.let { sb.append("Clima: $it. ") }
        routeWeather?.sunsetText?.let { sb.append("$it. ") }
        fuelLeftKm?.let { sb.append("Autonomia estimada do tanque: $it km. ") }
        if (gpsLost) sb.append("Sinal de GPS fraco agora. ")
        sb.toString()
    }

    private suspend fun jarvisSearch(q: String): String {
        if (q.isBlank()) return "Faltou dizer o que procurar."
        val here = withContext(Dispatchers.Main) { myLocation }
        val found = Geocoder.search(q, here).take(5)
        if (found.isEmpty()) return "Não encontrei nada para \"$q\"."
        return found.joinToString("; ") { "${it.name} (${it.detail})" }
    }

    private suspend fun jarvisWhereAmI(): String {
        val here = withContext(Dispatchers.Main) { myLocation } ?: return "Estou sem GPS agora."
        return Geocoder.reverse(here) ?: "Não consegui descobrir o nome do lugar (sem internet?)."
    }

    companion object {
        private const val LocationManagerProviderGps = "gps"
    }
}

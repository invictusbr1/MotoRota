package com.motorota.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorota.AppViewModel
import com.motorota.nav.Instructions
import com.motorota.routing.GeoPoint
import com.motorota.routing.Mode
import com.motorota.routing.Waypoint
import com.motorota.routing.formatDistance
import com.motorota.routing.formatDuration
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

val Bg = Color(0xFF101214)
val Panel = Color(0xF21A1E22)
val PanelBorder = Color(0xFF2C3238)
val TextDim = Color(0xFF9AA3AB)
val Amber = Color(0xFF4C8DF6) // cor de destaque (azul)
val NavGreen = Color(0xFF0B7A45)
val Blue = Color(0xFF1A73E8)
val Danger = Color(0xFFE53935)

fun Mode.color() = Color(0xFF4C8DF6)

@Composable
fun MainScreen(vm: AppViewModel, hasLocationPermission: Boolean) {
    var showDownloads by remember { mutableStateOf(false) }
    var pressPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var showTrips by remember { mutableStateOf(false) }
    var showSaveTrip by remember { mutableStateOf(false) }
    var reportAt by remember { mutableStateOf<GeoPoint?>(null) }
    var pickedStop by remember { mutableStateOf<Int?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    // atualiza o progresso dos downloads de mapa
    LaunchedEffect(Unit) {
        while (true) {
            vm.pollDownloads()
            delay(if (vm.downloadProgress.pending > 0) 1500 else 5000)
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        MapLibreMapView(
            vm = vm,
            hasLocationPermission = hasLocationPermission,
            bottomPaddingDp = if (vm.destination != null) 300 else 40,
            onLongPress = { if (!vm.navigating && !vm.editMode) pressPoint = it },
            onTap = { p, hit ->
                if (vm.editMode) {
                    val moving = vm.movingStop
                    when {
                        moving != null -> vm.moveStopTo(moving, p)
                        hit != null -> pickedStop = hit
                        else -> vm.addViaSmart(p)
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        val onAction: (QuickAction) -> Unit = { a ->
            when (a) {
                QuickAction.PASSEIOS -> showTrips = true
                QuickAction.TEMA -> vm.night = !vm.night
                QuickAction.SATELITE -> vm.toggleSatellite()
                QuickAction.AVISAR -> vm.myLocation?.let { reportAt = it } ?: run { vm.message = "Sem GPS." }
                QuickAction.VOZ -> vm.toggleVoice()
                QuickAction.ABASTECI -> if (vm.tankKm > 0) vm.refuel() else {
                    vm.message = "Primeiro informe a autonomia do tanque em Ajustes."
                    showSettings = true
                }
                QuickAction.GERAL -> vm.overview()
                QuickAction.MAPAS -> showDownloads = true
                QuickAction.AJUSTES -> showSettings = true
                QuickAction.SAIR -> vm.stopNavigation()
            }
        }
        if (vm.navigating) {
            NavigationOverlay(vm, onAction = onAction, onMenu = { showMenu = true })
        } else {
            PlanningOverlay(
                vm,
                onOpenDownloads = { showDownloads = true },
                onOpenTrips = { showTrips = true },
                onSaveTrip = { showSaveTrip = true },
                onAction = onAction,
                onMenu = { showMenu = true },
            )
        }

        vm.message?.let { msg ->
            LaunchedEffect(msg) {
                delay(4500); vm.message = null
            }
            Snackbar(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                containerColor = Color(0xFF2A3036),
                contentColor = Color.White,
            ) { Text(msg, fontSize = 16.sp) }
        }
    }

    pressPoint?.let { p ->
        LongPressDialog(vm, p, onReport = { reportAt = p }, onDismiss = { pressPoint = null })
    }
    reportAt?.let { p ->
        HazardDialog(vm, p, onDismiss = { reportAt = null })
    }
    pickedStop?.let { i ->
        StopActionDialog(vm, i, onDismiss = { pickedStop = null })
    }
    if (showTrips) TripsDialog(vm, onDismiss = { showTrips = false })
    if (showMenu) QuickMenuDialog(vm, nav = vm.navigating, onAction = { a ->
        // o menu tambem precisa das acoes: reaproveita as mesmas da tela
        when (a) {
            QuickAction.PASSEIOS -> showTrips = true
            QuickAction.AVISAR -> vm.myLocation?.let { reportAt = it } ?: run { vm.message = "Sem GPS." }
            QuickAction.ABASTECI -> if (vm.tankKm > 0) vm.refuel() else showSettings = true
            QuickAction.MAPAS -> showDownloads = true
            QuickAction.AJUSTES -> showSettings = true
            QuickAction.TEMA -> vm.night = !vm.night
            QuickAction.SATELITE -> vm.toggleSatellite()
            QuickAction.VOZ -> vm.toggleVoice()
            QuickAction.GERAL -> vm.overview()
            QuickAction.SAIR -> vm.stopNavigation()
        }
    }, onDismiss = { showMenu = false })
    if (showSettings) SettingsDialog(vm, onDismiss = {
        showSettings = false
        // nao deixa o teclado reabrir na busca depois de digitar a autonomia
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    })
    vm.tripSummary?.let { TripSummaryDialog(it, onDismiss = { vm.tripSummary = null }) }
    if (showSaveTrip) SaveTripDialog(vm, onDismiss = { showSaveTrip = false })
    if (showDownloads) {
        DownloadDialog(vm, onDismiss = { showDownloads = false })
    }
}

// ------------------------------------------------------------------
// Planejamento: busca, modos e resumo da rota
// ------------------------------------------------------------------

@Composable
private fun PlanningOverlay(
    vm: AppViewModel,
    onOpenDownloads: () -> Unit,
    onOpenTrips: () -> Unit,
    onSaveTrip: () -> Unit,
    onAction: (QuickAction) -> Unit,
    onMenu: () -> Unit,
) {
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // barra de busca
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; vm.search(it) },
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Panel),
                placeholder = { Text("Para onde vamos?", fontSize = 18.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (vm.searching) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else if (query.isNotEmpty()) IconButton(onClick = { query = ""; vm.clearSearch() }) {
                        Icon(Icons.Default.Close, "Limpar")
                    }
                },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, color = Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query); focus.clearFocus() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Panel, unfocusedContainerColor = Panel,
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                ),
            )
            Spacer(Modifier.width(8.dp))
            RoundButton(onClick = onOpenDownloads, highlight = !vm.hasMaps || vm.downloadProgress.pending > 0) {
                Icon(Icons.Default.Download, "Mapas", tint = Color.White)
            }
        }

        if (vm.searchResults.isNotEmpty()) {
            Surface(
                color = Panel,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.padding(horizontal = 12.dp).heightIn(max = 380.dp),
            ) {
                LazyColumn {
                    items(vm.searchResults) { place ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    focus.clearFocus()
                                    query = place.name
                                    if (vm.destination == null) vm.goTo(Waypoint(place.point, place.name))
                                    else vm.addViaSmart(place.point).also { vm.clearSearch() }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(place.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            if (place.detail.isNotBlank()) Text(place.detail, color = TextDim, fontSize = 13.sp)
                        }
                        HorizontalDivider(color = PanelBorder)
                    }
                }
            }
            if (vm.destination != null) {
                Text(
                    "Toque num resultado para adicionar como parada.",
                    color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(16.dp, 6.dp)
                )
            }
        }

        vm.update?.let { up ->
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Surface(
                color = Color(0xF20B3D91), shape = RoundedCornerShape(14.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Nova versão disponível (${up.version})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Os mapas e passeios continuam salvos.", color = Color(0xFFBBD3F5), fontSize = 13.sp)
                    }
                    Button(onClick = {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(up.apkUrl)))
                    }) { Text("Atualizar") }
                    IconButton(onClick = { vm.update = null }) { Icon(Icons.Default.Close, "Agora não", tint = Color.White) }
                }
            }
        }

        if (!vm.hasMaps && vm.downloadProgress.pending == 0) {
            Surface(
                color = Color(0xF2332A10), shape = RoundedCornerShape(14.dp),
                modifier = Modifier.padding(12.dp).fillMaxWidth().clickable { onOpenDownloads() },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Primeiro passo: baixar os mapas", color = Amber, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(
                        "As rotas são calculadas no celular, sem internet. Toque aqui para baixar Brasil, Argentina, Chile e Uruguai.",
                        color = Color.White, fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundButton(onClick = onMenu) { Text("☰", fontSize = 24.sp, color = Color.White) }
            PinnedButtons(vm, nav = false, onAction = onAction)
            RoundButton(onClick = { vm.centerOnMe() }) { Icon(Icons.Default.MyLocation, "Minha posição", tint = Color.White) }
        }

        if (vm.destination != null && vm.editMode) EditBar(vm, onSaveTrip)
        else if (vm.destination != null) RoutePanel(vm, onSaveTrip)
        else Text(
            "Dica: toque e segure no mapa para marcar destino, parada ou área de risco.",
            color = TextDim, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().background(Panel).padding(14.dp),
        )
    }
}

@Composable
private fun RoutePanel(vm: AppViewModel, onSaveTrip: () -> Unit) {
    // o painel ocupa no maximo ~60% da tela: o meio rola e o INICIAR fica sempre visivel embaixo
    val maxH = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.6f).dp
    Surface(
        color = Panel,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = maxH),
    ) {
        Column(Modifier.padding(14.dp)) {
          Column(
              Modifier.weight(1f, fill = false)
                  .verticalScroll(androidx.compose.foundation.rememberScrollState())
          ) {
            // os 3 modos
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { m -> ModeCard(vm, m, Modifier.weight(1f)) }
            }
            vm.currentRoute?.note?.let { Text(it, color = Color(0xFFFFB74D), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(12.dp))

            val r = vm.currentRoute
            val err = vm.routeErrors[vm.mode]
            when {
                r != null -> {
                    val eta = LocalTime.now().plusSeconds(r.timeS.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Stat("🛣️", "${r.highwayPct}% rodovia")
                        Stat("🟫", if (r.unpavedM < 100) "sem terra" else "${formatDistance(r.unpavedM.toDouble())} terra")
                        Stat("🕐", "chega $eta")
                    }
                }
                err != null -> Text(err, color = Danger, fontSize = 15.sp)
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Calculando a rota ${vm.mode.label.lowercase()}…", color = Color.White)
                }
            }

            Spacer(Modifier.height(10.dp))
            // pontos
            WaypointLine("🟢", vm.origin?.name ?: "Minha localização (GPS)",
                onRemove = if (vm.origin != null) ({ vm.setOriginHere(null) }) else null)
            vm.stops.forEachIndexed { i, w ->
                WaypointLine(
                    "🟡", "Parada ${i + 1}: ${w.name}",
                    onRemove = { vm.removeStop(i) },
                    onUp = if (i > 0) ({ vm.moveStopUp(i) }) else null,
                    onDown = if (i < vm.stops.size - 1) ({ vm.moveStopDown(i) }) else null,
                )
            }
            Button(
                onClick = { vm.editMode = true },
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(vertical = 2.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3036), contentColor = Color.White),
            ) { Text("✏️  Editar caminho (tocar por onde passar)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.makeRoundTrip() }, modifier = Modifier.height(44.dp)) { Text("🔁 Ida e volta") }
                OutlinedButton(onClick = onSaveTrip, modifier = Modifier.height(44.dp)) { Text("💾 Salvar passeio") }
            }
            vm.destination?.let { WaypointLine("🔴", it.name, onRemove = null) }
            // informacoes da estrada (ficam embaixo dos botoes; o painel rola)
            vm.currentRoute?.let { r ->
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = PanelBorder)
                Spacer(Modifier.height(6.dp))
                Text("${vm.mode.emoji} ${vm.mode.label}: ${vm.mode.hint}", color = TextDim, fontSize = 12.sp)
                if (r.badPavingM > 500) {
                    Text("🧱 ${formatDistance(r.badPavingM.toDouble())} de paralelepípedo/bloquete", color = TextDim, fontSize = 13.sp)
                }
                // contas pesadas (radares, trechos) fora da tela principal, para nao travar
                val alerts by androidx.compose.runtime.produceState(emptyList<com.motorota.nav.AlongAlert>(), r, vm.hazards, vm.liveIncidents, vm.knownRisksOn) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { vm.alertsOn(r) }
                }
                val nRadar = alerts.count { it.isRadar }
                val nOther = alerts.size - nRadar
                if (alerts.isNotEmpty()) {
                    Text(
                        listOfNotNull(
                            if (nRadar > 0) "📷 $nRadar radar(es)" else null,
                            if (nOther > 0) "⚠️ $nOther alerta(s)" else null,
                        ).joinToString("  ·  ") + " no caminho",
                        color = TextDim, fontSize = 13.sp,
                    )
                }
                if (vm.risks.isNotEmpty()) {
                    Text("⚠️ Desviando de ${vm.risks.size} área(s) de risco", color = TextDim, fontSize = 13.sp)
                }
                // areas de risco conhecidas pelas quais a rota passa ao lado (via expressa)
                val nKnown = alerts.count { it.key.startsWith("kr-") }
                // rodovias com muitos assaltos por onde a rota passa
                val coHits by androidx.compose.runtime.produceState(emptyList<com.motorota.data.CorridorHit>(), r, vm.knownRisksOn) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { vm.corridorHits(r) }
                }
                coHits.groupBy { it.corridor.name.substringBefore(" (") }.forEach { (nome, hs) ->
                    val km = (hs.sumOf { it.lengthM } / 1000).coerceAtLeast(1.0)
                    Text(
                        "🚨 Passa ${"%.0f".format(km)} km pela $nome: muitos assaltos, evite parar",
                        color = Color(0xFFFF8A80), fontSize = 13.sp,
                    )
                }
                if (nKnown > 0) {
                    Text("🚨 Passa ao lado de $nKnown área(s) de risco: aviso por voz, evite parar", color = Color(0xFFFF8A80), fontSize = 13.sp)
                }
                // combustivel
                vm.fuelLeftKm?.let { left ->
                    if (r.distanceM / 1000 > left) {
                        Text("⛽ Autonomia ~$left km: vai precisar abastecer no caminho", color = Color(0xFFFFB74D), fontSize = 13.sp)
                    }
                }
                // clima e por do sol
                vm.routeWeather?.rainText?.let { Text(it, color = if (it.startsWith("🌧")) Color(0xFF90CAF9) else TextDim, fontSize = 13.sp) }
                vm.routeWeather?.sunsetText?.let { Text(it, color = Color(0xFFFFCC80), fontSize = 13.sp) }
                // transito de agora (TomTom)
                vm.trafficStatus?.let { Text(it, color = TextDim, fontSize = 13.sp) }
                vm.liveIncidents.take(4).forEach { inc ->
                    Text(
                        "${inc.emoji} ${inc.title}" + if (inc.where.isNotBlank()) " · ${inc.where}" else "",
                        color = Color(0xFFFFB74D), fontSize = 13.sp, maxLines = 1,
                    )
                }
                if (vm.liveIncidents.size > 4) {
                    Text("… e mais ${vm.liveIncidents.size - 4}", color = TextDim, fontSize = 13.sp)
                }
            }
          }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { vm.clearRoute() },
                    modifier = Modifier.height(60.dp),
                ) { Text("Cancelar", fontSize = 16.sp) }
                Button(
                    onClick = { vm.startNavigation() },
                    enabled = vm.currentRoute != null,
                    modifier = Modifier.weight(1f).height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White),
                ) { Text("▶  INICIAR", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun ModeCard(vm: AppViewModel, m: Mode, modifier: Modifier) {
    val selected = vm.mode == m
    val r = vm.routes[m]
    val busy = m in vm.calculating && r == null
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) m.color().copy(alpha = 0.22f) else Color(0xFF22272C))
            .border(2.dp, if (selected) m.color() else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable { vm.selectMode(m) }
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(m.emoji, fontSize = 20.sp)
        Text(m.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, softWrap = false)
        Spacer(Modifier.height(4.dp))
        when {
            r != null -> {
                Text(formatDistance(r.distanceM.toDouble()), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(formatDuration(r.timeS), color = TextDim, fontSize = 14.sp)
                // quando o melhor caminho e o mesmo em dois modos, avisa (nao e defeito do botao)
                val same = Mode.entries.firstOrNull { o ->
                    o.ordinal < m.ordinal && vm.routes[o]?.let { it.distanceM == r.distanceM && it.timeS == r.timeS } == true
                }
                if (same != null) Text("= ${same.label}", color = TextDim, fontSize = 11.sp, maxLines = 1)
            }
            busy -> CircularProgressIndicator(Modifier.size(22.dp).padding(2.dp), strokeWidth = 2.dp, color = m.color())
            vm.routeErrors[m] != null -> Text("sem rota", color = Danger, fontSize = 14.sp)
            else -> Text("—", color = TextDim)
        }
    }
}

@Composable
private fun Stat(icon: String, text: String) {
    Text("$icon $text", color = Color.White, fontSize = 15.sp)
}

@Composable
private fun WaypointLine(dot: String, text: String, onRemove: (() -> Unit)?, onUp: (() -> Unit)? = null, onDown: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(dot, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onUp != null) IconButton(onClick = onUp) { Text("⬆", color = TextDim, fontSize = 18.sp) }
        if (onDown != null) IconButton(onClick = onDown) { Text("⬇", color = TextDim, fontSize = 18.sp) }
        if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remover", tint = TextDim) }
    }
}

@Composable
fun RoundButton(onClick: () -> Unit, highlight: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(if (highlight) Amber.copy(alpha = 0.85f) else Panel)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ------------------------------------------------------------------
// Navegacao: instrucao grande em cima, velocidade e tempo embaixo
// ------------------------------------------------------------------

@Composable
private fun NavigationOverlay(vm: AppViewModel, onAction: (QuickAction) -> Unit, onMenu: () -> Unit) {
    val st = vm.navState
    val navBg = Color(0xF20F1316)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // ---- instrucao (faixa escura em cima, como no Waze) ----
        Surface(color = navBg, modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Instructions.arrow(st?.nextHint), fontSize = 60.sp, color = Color.White)
                    Spacer(Modifier.width(18.dp))
                    Column {
                        when {
                            vm.rerouting -> Text("Recalculando…", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            st == null -> Text("Aguardando GPS…", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            else -> {
                                Text(formatDistance(st.distToHintM), fontSize = 40.sp, fontWeight = FontWeight.Black, color = Color.White)
                                Text(
                                    st.nextHint?.let { Instructions.text(it).replaceFirstChar { c -> c.uppercase() } } ?: "Chegada ao destino",
                                    fontSize = 22.sp, color = Color(0xFF4FC3F7), fontWeight = FontWeight.Bold, maxLines = 2,
                                )
                            }
                        }
                    }
                }
                // "em seguida": a manobra depois dessa, quando vem logo em seguida
                val r = vm.currentRoute
                val cur = st?.nextHint
                val after = if (r != null && cur != null) r.hints.firstOrNull { it.index > cur.index } else null
                if (r != null && cur != null && after != null) {
                    val gap = r.cumDist[after.index.coerceAtMost(r.cumDist.size - 1)] - r.cumDist[cur.index.coerceAtMost(r.cumDist.size - 1)]
                    if (gap < 800) {
                        HorizontalDivider(color = Color(0xFF2A3036))
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("em seguida", fontSize = 17.sp, color = Color.White)
                            Spacer(Modifier.width(12.dp))
                            Text(Instructions.arrow(after), fontSize = 26.sp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text(Instructions.text(after), fontSize = 15.sp, color = TextDim, maxLines = 1)
                        }
                    }
                }
            }
        }

        // radar / alerta a frente
        vm.nextAlert?.let { (a, d) ->
            Surface(
                color = if (a.isRadar) Color(0xF2B71C1C) else Color(0xF2E65100),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.emoji, fontSize = 28.sp)
                    Spacer(Modifier.width(10.dp))
                    Text("${a.title}  ·  ${formatDistance(d)}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // ---- logo acima do painel: placa do limite (esquerda) e botoes fixados (direita) ----
        val limit = vm.currentLimit ?: vm.nextAlert?.takeIf { it.first.isRadar && it.second < 400 }?.first?.limitKmh?.takeIf { it > 0 }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            SpeedLimitSign(limit)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PinnedButtons(vm, nav = true, onAction = onAction)
            }
        }

        // ---- painel de baixo ----
        Surface(color = navBg, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SpeedGauge(vm.speedKmh, limit, Modifier.size(118.dp))
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    val resumo = st?.let { "${formatDuration(it.remainingS)}  •  ${formatDistance(it.remainingM)}" } ?: ""
                    if (!vm.following) {
                        // arrastou o mapa ou esta na visao geral: botao grande para voltar a seguir a moto
                        Row(
                            Modifier.clip(RoundedCornerShape(14.dp)).clickable { vm.recenter() }.padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(46.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.MyLocation, "Re-centralizar", tint = Color.Black)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Re-centralizar", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                                Text(resumo, fontSize = 15.sp, color = TextDim, maxLines = 1)
                            }
                        }
                    } else if (st != null) {
                        val eta = LocalTime.now().plusSeconds(st.remainingS.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(eta, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text("chegada", fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(bottom = 5.dp))
                        }
                        Text(resumo, fontSize = 15.sp, color = TextDim, maxLines = 1)
                    }
                    if (vm.stops.isNotEmpty()) Text("Próxima parada: ${vm.stops.first().name}", fontSize = 13.sp, color = Amber, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    // computador de bordo
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CockpitChip("🛣️", formatDistance(vm.tripDistM))
                        if (vm.tripAvgKmh > 0) CockpitChip("📊", "${vm.tripAvgKmh}")
                        vm.fuelLeftKm?.let { left ->
                            CockpitChip("⛽", "${left.coerceAtLeast(0)} km", highlight = if (left <= 60) Color(0xFF8D4A00) else null)
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vm.following) {
                        FilledTonalButton(
                            onClick = { vm.overview() },
                            modifier = Modifier.height(48.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF2A3036), contentColor = Color(0xFF4FC3F7)),
                        ) { Text("Geral", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                    }
                    RoundButton(onClick = onMenu) { Text("☰", fontSize = 24.sp, color = Color.White) }
                }
            }
        }
    }
}

/** Placa de limite de velocidade, sempre no mesmo lugar ("--" quando a via nao informa o limite). */
@Composable
private fun SpeedLimitSign(limit: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(76.dp).clip(CircleShape).background(Color.White)
                .border(7.dp, if (limit != null) Danger else Color(0xFF9AA0A6), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                limit?.toString() ?: "--", fontSize = if ((limit ?: 0) >= 100) 24.sp else 28.sp,
                fontWeight = FontWeight.Black, color = if (limit != null) Color.Black else Color(0xFF9AA0A6),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text("limite", fontSize = 11.sp, color = Color.White,
            modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
    }
}


// ------------------------------------------------------------------
// Edicao do caminho: toque no mapa = passar por ali
// ------------------------------------------------------------------

@Composable
private fun EditBar(vm: AppViewModel, onSaveTrip: () -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("✏️ Editando o caminho", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            val moving = vm.movingStop
            Text(
                if (moving != null) "Toque no mapa no novo lugar do ponto ${moving + 1}."
                else "Toque no mapa onde o caminho deve passar: o ponto entra na ordem certa sozinho. " +
                    "Toque num ponto (círculo branco) para mover ou remover.",
                color = if (moving != null) Amber else TextDim, fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            val r = vm.currentRoute
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vm.calculating.contains(vm.mode) && r == null) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Recalculando…", color = Color.White, fontSize = 15.sp)
                } else if (r != null) {
                    Text(
                        "${vm.mode.emoji} ${formatDistance(r.distanceM.toDouble())} · ${formatDuration(r.timeS)} · " +
                            "${vm.stops.size} ponto(s) de passagem",
                        color = Color.White, fontSize = 15.sp,
                    )
                } else vm.routeErrors[vm.mode]?.let { Text(it, color = Danger, fontSize = 14.sp) }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.undo() },
                    enabled = vm.canUndo,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("↶ Desfazer", fontSize = 15.sp, maxLines = 1) }
                if (moving != null) OutlinedButton(
                    onClick = { vm.movingStop = null },
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("Cancelar", fontSize = 15.sp, maxLines = 1) }
                else OutlinedButton(
                    onClick = onSaveTrip,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("💾 Salvar", fontSize = 15.sp, maxLines = 1) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.finishEditing() },
                    modifier = Modifier.weight(1f).height(58.dp),
                ) { Text("✓ Pronto", fontSize = 16.sp, maxLines = 1) }
                Button(
                    onClick = { vm.startFromEditing() },
                    modifier = Modifier.weight(1.4f).height(58.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White),
                ) { Text("▶  INICIAR", fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            }
        }
    }
}

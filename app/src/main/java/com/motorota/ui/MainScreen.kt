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

        if (vm.navigating) {
            NavigationOverlay(vm, onReport = { vm.myLocation?.let { reportAt = it } ?: run { vm.message = "Sem GPS." } })
        } else {
            PlanningOverlay(
                vm,
                onOpenDownloads = { showDownloads = true },
                onOpenTrips = { showTrips = true },
                onSaveTrip = { showSaveTrip = true },
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
            RoundButton(onClick = onOpenTrips) { Text("⭐", fontSize = 22.sp) }
            RoundButton(onClick = { vm.night = !vm.night }) { Text(if (vm.night) "☀️" else "🌙", fontSize = 22.sp) }
            RoundButton(onClick = { vm.toggleSatellite() }, highlight = vm.satellite) { Text("🛰️", fontSize = 22.sp) }
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
                    if (r.badPavingM > 500) {
                        Text("🧱 ${formatDistance(r.badPavingM.toDouble())} de paralelepípedo/bloquete", color = TextDim, fontSize = 13.sp)
                    }
                    val alerts = remember(r, vm.hazards, vm.liveIncidents) { vm.alertsOn(r) }
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
private fun NavigationOverlay(vm: AppViewModel, onReport: () -> Unit) {
    val st = vm.navState
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Surface(color = NavGreen, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Instructions.arrow(st?.nextHint), fontSize = 58.sp, color = Color.White)
                Spacer(Modifier.width(14.dp))
                Column {
                    if (vm.rerouting) {
                        Text("Recalculando…", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    } else if (st == null) {
                        Text("Aguardando GPS…", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    } else {
                        Text(formatDistance(st.distToHintM), fontSize = 40.sp, fontWeight = FontWeight.Black, color = Color.White)
                        Text(
                            st.nextHint?.let { Instructions.text(it).replaceFirstChar { c -> c.uppercase() } } ?: "Chegada ao destino",
                            fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // radar / alerta a frente
        vm.nextAlert?.let { (a, d) ->
            Surface(
                color = if (a.isRadar) Color(0xF2B71C1C) else Color(0xF2E65100),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.padding(horizontal = 10.dp),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.emoji, fontSize = 28.sp)
                    Spacer(Modifier.width(10.dp))
                    Text("${a.title}  ·  ${formatDistance(d)}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            // placa de limite da via
            val limit = vm.currentLimit ?: vm.nextAlert?.takeIf { it.first.isRadar && it.second < 400 }?.first?.limitKmh?.takeIf { it > 0 }
            if (limit != null) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(Color.White).border(7.dp, Danger, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("$limit", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.Black) }
            } else Spacer(Modifier.size(72.dp))
            // botao de avisar (tipo Waze)
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(Panel).border(2.dp, PanelBorder, CircleShape).clickable(onClick = onReport),
                contentAlignment = Alignment.Center,
            ) { Text("⚠️", fontSize = 32.sp) }
        }

        Surface(color = Panel, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                val over = vm.currentLimit?.let { vm.speedKmh > it + 5 } == true
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
                    Text("${vm.speedKmh}", fontSize = 40.sp, fontWeight = FontWeight.Black, color = if (over) Danger else Color.White, maxLines = 1, softWrap = false)
                    Text("km/h", fontSize = 13.sp, color = TextDim)
                }
                Column(Modifier.weight(1f)) {
                    if (st != null) {
                        val eta = LocalTime.now().plusSeconds(st.remainingS.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
                        Text(eta, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("${formatDistance(st.remainingM)} · ${formatDuration(st.remainingS)}", fontSize = 15.sp, color = TextDim)
                    }
                    if (vm.stops.isNotEmpty()) Text("Próxima parada: ${vm.stops.first().name}", fontSize = 13.sp, color = Amber, maxLines = 1)
                }
                RoundButton(onClick = { vm.toggleVoice() }) {
                    Icon(if (vm.voiceOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff, "Voz", tint = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = { vm.stopNavigation() },
                    modifier = Modifier.height(58.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Danger, contentColor = Color.White),
                ) { Text("Sair", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }
        }
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

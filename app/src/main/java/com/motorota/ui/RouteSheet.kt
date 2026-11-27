package com.motorota.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditRoad
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorota.AppViewModel
import com.motorota.data.CorridorHit
import com.motorota.nav.AlongAlert
import com.motorota.routing.Mode
import com.motorota.routing.formatDistance
import com.motorota.routing.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Painel inferior de planejamento de rota: organizado, recolhível e sem poluição visual.
 */
@Composable
fun RouteSheet(vm: AppViewModel, onSaveTrip: () -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    val screenH = LocalConfiguration.current.screenHeightDp
    val maxH = (screenH * if (expanded) 0.58f else 0.36f).dp

    val r = vm.currentRoute
    val err = vm.routeErrors[vm.mode]

    val alerts by produceState(emptyList<AlongAlert>(), r, vm.hazards, vm.liveIncidents, vm.knownRisksOn) {
        value = if (r != null) withContext(Dispatchers.Default) { vm.alertsOn(r) } else emptyList()
    }
    val coHits by produceState(emptyList<CorridorHit>(), r, vm.knownRisksOn) {
        value = if (r != null) withContext(Dispatchers.Default) { vm.corridorHits(r) } else emptyList()
    }

    Surface(
        color = Panel,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxH)
            .border(1.dp, PanelBorder, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .animateContentSize(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            // Alça superior + Cabeçalho compacto do destino
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(PanelBorder)
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).clickable { expanded = !expanded }) {
                    Text(
                        vm.destination?.name ?: "Destino",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = when {
                        r != null -> {
                            val eta = LocalTime.now().plusSeconds(r.timeS.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
                            "Chegada às $eta  ·  ${r.highwayPct}% rodovia"
                        }
                        err != null -> "Não foi possível calcular"
                        else -> "Calculando melhor trajeto…"
                    }
                    Text(subtitle, color = if (err != null) Danger else TextDim, fontSize = 13.sp, maxLines = 1)
                }

                IconButton(onClick = { vm.swapEndpoints() }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.SwapVert, contentDescription = "Inverter partida e destino", tint = TextDim)
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(38.dp)) {
                    Icon(
                        if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                        contentDescription = if (expanded) "Recolher detalhes" else "Ver detalhes",
                        tint = TextDim,
                    )
                }
                IconButton(onClick = { vm.clearRoute() }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cancelar rota", tint = TextDim)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Os 3 modos sempre visíveis
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { m -> ModeCard(vm, m, Modifier.weight(1f)) }
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (err != null) {
                        Surface(
                            color = Danger.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, Danger.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                        ) {
                            Text(err, color = Color(0xFFFCA5A5), fontSize = 14.sp, modifier = Modifier.padding(12.dp))
                        }
                    } else if (r == null) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = vm.mode.color())
                            Spacer(Modifier.width(10.dp))
                            Text("Calculando perfil ${vm.mode.label.lowercase()}…", color = TextDim, fontSize = 14.sp)
                        }
                    } else {
                        // Faixa horizontal de indicadores rápidos (sem poluir em várias linhas)
                        val nRadar = alerts.count { it.isRadar }
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            StatusPill(
                                icon = Icons.Rounded.Terrain,
                                text = if (r.unpavedM < 100) "100% asfalto" else "${formatDistance(r.unpavedM.toDouble())} terra",
                                tint = if (r.unpavedM < 100) NavGreen else WarningColor,
                            )
                            if (nRadar > 0) {
                                StatusPill(emoji = "📷", text = "$nRadar radares")
                            }
                            vm.routeWeather?.rainText?.let { rain ->
                                val isRain = rain.startsWith("🌧")
                                StatusPill(
                                    emoji = if (isRain) "🌧️" else "☀️",
                                    text = if (isRain) rain.removePrefix("🌧️ ") else "Sem chuva",
                                    tint = if (isRain) Color(0xFF60A5FA) else NavGreen,
                                )
                            }
                            vm.trafficStatus?.let { ts ->
                                StatusPill(emoji = "🚦", text = ts)
                            }
                            if (r.source == "TomTom") StatusPill(emoji = "🗺️", text = "Mapa atualizado")
                            vm.tripCost(r)?.let { c ->
                                StatusPill(
                                    emoji = "⛽",
                                    text = String.format(java.util.Locale("pt", "BR"), "~R$ %.0f · %.0f L", c.reais, c.liters) +
                                        if (c.refuels > 0) " · ${c.refuels} parada(s)" else "",
                                )
                            }
                        }

                        // Caixa consolidada de avisos importantes (só aparece quando há alertas reais)
                        val warnings = buildList {
                            r.note?.let { add(it) }
                            vm.fuelLeftKm?.let { left ->
                                if (r.distanceM / 1000 > left) {
                                    add("⛽ Autonomia (~$left km) menor que a viagem: programe parada para abastecer.")
                                }
                            }
                            vm.routeWeather?.sunsetText?.let { add(it) }
                            if (r.badPavingM > 500) {
                                add("🧱 ${formatDistance(r.badPavingM.toDouble())} de paralelepípedo ou bloquete.")
                            }
                            coHits.groupBy { it.corridor.name.substringBefore(" (") }.forEach { (nome, hs) ->
                                val km = (hs.sumOf { it.lengthM } / 1000).coerceAtLeast(1.0)
                                add("🚨 Passa ${"%.0f".format(km)} km pela $nome (alto índice de roubo · evite parar).")
                            }
                            val nKnown = alerts.count { it.key.startsWith("kr-") }
                            if (nKnown > 0) {
                                add("🚨 Margeia $nKnown área(s) de risco: siga na via principal sem parar.")
                            }
                            if (vm.risks.isNotEmpty()) {
                                add("🛡️ Desviando de ${vm.risks.size} área(s) bloqueada(s) por você.")
                            }
                            vm.liveIncidents.take(3).forEach { inc ->
                                add("${inc.emoji} ${inc.title}" + if (inc.where.isNotBlank()) " · ${inc.where}" else "")
                            }
                            if (vm.liveIncidents.size > 3) {
                                add("… e mais ${vm.liveIncidents.size - 3} ocorrência(s) de trânsito.")
                            }
                        }

                        if (warnings.isNotEmpty()) {
                            Surface(
                                color = SurfaceCard,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)),
                            ) {
                                Column(
                                    Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.WarningAmber, null, tint = WarningColor, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Avisos do trajeto", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                    warnings.forEach { w ->
                                        Text(w, color = TextDim, fontSize = 12.5.sp, lineHeight = 17.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Cartão unificado do Roteiro (Partida -> Paradas -> Destino no mesmo bloco!)
                    Surface(
                        color = SurfaceCard,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            ItineraryRow(
                                badgeColor = NavGreen,
                                badgeText = null,
                                title = vm.origin?.name ?: "Minha localização (GPS)",
                                onRemove = if (vm.origin != null) ({ vm.setOriginHere(null) }) else null,
                            )
                            vm.stops.forEachIndexed { i, w ->
                                HorizontalDivider(color = PanelBorder.copy(alpha = 0.5f))
                                ItineraryRow(
                                    badgeColor = Color.White,
                                    badgeText = "${i + 1}",
                                    title = w.name,
                                    onRemove = { vm.removeStop(i) },
                                    onUp = if (i > 0) ({ vm.moveStopUp(i) }) else null,
                                    onDown = if (i < vm.stops.size - 1) ({ vm.moveStopDown(i) }) else null,
                                )
                            }
                            vm.destination?.let { dest ->
                                HorizontalDivider(color = PanelBorder.copy(alpha = 0.5f))
                                ItineraryRow(
                                    badgeColor = Danger,
                                    badgeText = null,
                                    title = dest.name,
                                    onRemove = null,
                                )
                            }
                        }
                    }

                    // Botões de personalização do roteiro alinhados em grade limpa
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { vm.editMode = true },
                            modifier = Modifier.weight(1.2f).height(42.dp),
                            shape = RoundedCornerShape(11.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(Icons.Rounded.EditRoad, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Editar caminho", fontSize = 13.sp, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { vm.makeRoundTrip() },
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(11.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(Icons.Rounded.Loop, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Ida e volta", fontSize = 13.sp, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = onSaveTrip,
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(11.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(Icons.Rounded.BookmarkBorder, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Salvar passeio", fontSize = 13.sp, maxLines = 1)
                        }
                    }

                    Text(
                        "${vm.mode.emoji} ${vm.mode.label}: ${vm.mode.hint}",
                        color = TextDim,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Rodapé fixo com Cancelar + INICIAR
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { vm.clearRoute() },
                    modifier = Modifier.height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Cancelar", fontSize = 15.sp, color = TextPrimary)
                }
                Button(
                    onClick = { vm.startNavigation() },
                    enabled = vm.currentRoute != null,
                    modifier = Modifier.weight(1f).height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White),
                ) {
                    Icon(Icons.Rounded.Navigation, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("INICIAR", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ModeCard(vm: AppViewModel, m: Mode, modifier: Modifier) {
    val selected = vm.mode == m
    val r = vm.routes[m]
    val busy = m in vm.calculating && r == null
    val accent = m.color()

    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) accent.copy(alpha = 0.18f) else SurfaceCard)
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else PanelBorder, RoundedCornerShape(14.dp))
            .clickable { vm.selectMode(m) }
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.emoji, fontSize = 15.sp)
            Spacer(Modifier.width(4.dp))
            Text(
                m.label,
                color = if (selected) Color.White else TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
        Spacer(Modifier.height(4.dp))
        when {
            r != null -> {
                Text(
                    formatDuration(r.timeS),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                )
                val same = Mode.entries.firstOrNull { o ->
                    o.ordinal < m.ordinal && vm.routes[o]?.let { it.distanceM == r.distanceM && it.timeS == r.timeS } == true
                }
                Text(
                    if (same != null) "= ${same.label}" else formatDistance(r.distanceM.toDouble()),
                    color = if (selected) accent else TextDim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            busy -> CircularProgressIndicator(Modifier.size(20.dp).padding(2.dp), strokeWidth = 2.dp, color = accent)
            vm.routeErrors[m] != null -> Text("sem rota", color = Danger, fontSize = 12.sp)
            else -> Text("—", color = TextDim, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ItineraryRow(
    badgeColor: Color,
    badgeText: String?,
    title: String,
    onRemove: (() -> Unit)?,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 38.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(if (badgeText != null) 20.dp else 12.dp)
                .clip(CircleShape)
                .background(badgeColor),
            contentAlignment = Alignment.Center,
        ) {
            if (badgeText != null) {
                Text(badgeText, color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            color = Color.White,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onUp != null) {
            IconButton(onClick = onUp, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.KeyboardArrowUp, "Subir parada", tint = TextDim, modifier = Modifier.size(20.dp))
            }
        }
        if (onDown != null) {
            IconButton(onClick = onDown, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.KeyboardArrowDown, "Descer parada", tint = TextDim, modifier = Modifier.size(20.dp))
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, "Remover", tint = TextDim, modifier = Modifier.size(17.dp))
            }
        }
    }
}

/** Barra inferior dedicada ao modo de edição de paradas tocando no mapa. */
@Composable
fun EditBar(vm: AppViewModel, onSaveTrip: () -> Unit) {
    val moving = vm.movingStop
    val r = vm.currentRoute

    Surface(
        color = Panel,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, PanelBorder, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.EditRoad, null, tint = Color(0xFF60A5FA), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Editando o caminho", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (moving != null) "Toque no mapa para definir a nova posição da parada ${moving + 1}."
                else "Toque no mapa por onde deseja passar. Toque numa parada numerada para mover ou excluir.",
                color = if (moving != null) Color(0xFF60A5FA) else TextDim,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vm.calculating.contains(vm.mode) && r == null) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Recalculando…", color = Color.White, fontSize = 14.sp)
                } else if (r != null) {
                    Icon(Icons.Rounded.Schedule, null, tint = TextDim, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${formatDistance(r.distanceM.toDouble())} · ${formatDuration(r.timeS)} · ${vm.stops.size} parada(s)",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    vm.routeErrors[vm.mode]?.let { Text(it, color = Danger, fontSize = 13.sp) }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.undo() },
                    enabled = vm.canUndo,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Desfazer", fontSize = 14.sp, maxLines = 1)
                }
                if (moving != null) {
                    OutlinedButton(
                        onClick = { vm.movingStop = null },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Cancelar", fontSize = 14.sp, maxLines = 1) }
                } else {
                    OutlinedButton(
                        onClick = onSaveTrip,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Rounded.BookmarkBorder, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Salvar", fontSize = 14.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.finishEditing() },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Pronto", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Button(
                    onClick = { vm.startFromEditing() },
                    modifier = Modifier.weight(1.4f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White),
                ) {
                    Icon(Icons.Rounded.Navigation, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("INICIAR", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

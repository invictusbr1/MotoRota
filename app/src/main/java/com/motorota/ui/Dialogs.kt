package com.motorota.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsMotorsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorota.AppViewModel
import com.motorota.data.Keys
import com.motorota.assistant.JarvisVoice
import com.motorota.assistant.PiperVoice
import com.motorota.R
import com.motorota.data.Corridors
import com.motorota.data.HazardType
import com.motorota.data.KnownRisks
import com.motorota.data.MapPacks
import com.motorota.routing.GeoPoint
import com.motorota.routing.Waypoint
import com.motorota.routing.formatDistance
import com.motorota.routing.formatDuration
import java.util.Locale

private val DialogBg = Color(0xFF161B22)

@Composable
private fun ActionRowButton(
    icon: ImageVector,
    text: String,
    containerColor: Color = SurfaceCard,
    contentColor: Color = Color.White,
    iconTint: Color = contentColor,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun LongPressDialog(vm: AppViewModel, p: GeoPoint, onReport: () -> Unit, onDismiss: () -> Unit) {
    var askRadius by remember { mutableStateOf(false) }
    val near = vm.riskNear(p)
    val coords = String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogBg,
        title = {
            Column {
                Text(
                    if (askRadius) "Raio da área de risco" else "Ponto selecionado",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Text(coords, color = TextDim, fontSize = 12.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!askRadius) {
                    ActionRowButton(
                        icon = Icons.Rounded.Flag,
                        text = "Ir para cá",
                        containerColor = NavGreen,
                        contentColor = Color(0xFF042617),
                    ) {
                        vm.goTo(Waypoint(p, "Ponto no mapa ($coords)")); onDismiss()
                    }
                    if (vm.destination != null) {
                        ActionRowButton(icon = Icons.Rounded.Place, text = "Passar por aqui", iconTint = Color(0xFF60A5FA)) {
                            vm.addViaSmart(p, "Parada no mapa"); onDismiss()
                        }
                    }
                    ActionRowButton(icon = Icons.Rounded.MyLocation, text = "Sair daqui (definir partida)", iconTint = NavGreen) {
                        vm.setOriginHere(Waypoint(p, "Partida ($coords)")); onDismiss()
                    }
                    ActionRowButton(
                        icon = Icons.Rounded.ReportProblem,
                        text = "Avisar: buraco, polícia, obra…",
                        containerColor = Color(0xFF7C2D12),
                        iconTint = Color(0xFFFDBA74),
                    ) {
                        onDismiss(); onReport()
                    }
                    ActionRowButton(
                        icon = Icons.Rounded.Block,
                        text = "Marcar área de risco",
                        containerColor = Color(0xFF7F1D1D),
                        iconTint = Color(0xFFFCA5A5),
                    ) {
                        askRadius = true
                    }
                    vm.hazardNear(p)?.let { h ->
                        ActionRowButton(icon = Icons.Rounded.DeleteOutline, text = "Remover aviso: ${h.type.label}") {
                            vm.removeHazard(h.id); onDismiss()
                        }
                    }
                    if (near != null) {
                        ActionRowButton(icon = Icons.Rounded.DeleteOutline, text = "Remover esta área de risco") {
                            vm.removeRisk(near.id); onDismiss()
                        }
                    }
                } else {
                    Text("As rotas nunca atravessarão o círculo demarcado.", color = TextDim, fontSize = 13.sp)
                    listOf(
                        300 to "Pequena — 300 m (quarteirão / rua)",
                        800 to "Média — 800 m (bairro compacto)",
                        2000 to "Grande — 2 km (região inteira)",
                    ).forEach { (r, label) ->
                        ActionRowButton(icon = Icons.Rounded.Block, text = label, iconTint = Danger) {
                            vm.addRisk(p, r, "Área de risco"); onDismiss()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

@Composable
fun DownloadDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val prog = vm.downloadProgress
    @Suppress("UNUSED_VARIABLE") val refresh = vm.mapsVersion

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogBg,
        title = { Text("Mapas offline de rota", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Baixe uma vez no Wi-Fi para calcular e recalcular rotas 100% sem internet. " +
                        "O download continua em segundo plano.",
                    color = TextDim,
                    fontSize = 13.5.sp,
                )
                if (prog.pending > 0) {
                    val frac = if (prog.bytesTotal > 0) (prog.bytesDone.toFloat() / prog.bytesTotal).coerceIn(0f, 1f) else 0f
                    Surface(color = SurfaceCard, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Baixando ${prog.pending} bloco(s)… ${prog.bytesDone / 1_048_576} MB",
                                color = Color(0xFF60A5FA),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            if (prog.bytesTotal > 0) {
                                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }

                MapPacks.countries.forEach { c ->
                    val have = vm.downloader.installedCount(c)
                    val complete = have == c.tiles.size
                    Surface(
                        color = SurfaceCard,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(12.dp)),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${c.flag} ${c.name}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (complete) "Instalado (${c.sizeMb} MB)" else "${c.sizeMb} MB · $have/${c.tiles.size} partes",
                                    color = if (complete) NavGreen else TextDim,
                                    fontSize = 12.5.sp,
                                )
                            }
                            if (!complete) {
                                OutlinedButton(
                                    onClick = { vm.downloader.download(c.tiles); vm.pollDownloads() },
                                    shape = RoundedCornerShape(10.dp),
                                ) { Text("Baixar") }
                            } else {
                                Icon(Icons.Rounded.CheckCircle, null, tint = NavGreen, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(4.dp))
                                IconButton(
                                    onClick = {
                                        val keep = MapPacks.countries
                                            .filter { it != c && vm.downloader.installedCount(it) > 0 }
                                            .flatMap { it.tiles }
                                            .toSet()
                                        vm.downloader.deleteCountry(c, keep)
                                        vm.pollDownloads()
                                    },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(Icons.Rounded.DeleteOutline, "Remover mapa", tint = TextDim, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                val all = MapPacks.countries.flatMap { it.tiles }.distinct()
                if (all.any { !vm.downloader.isInstalled(it) }) {
                    Button(
                        onClick = { vm.downloader.download(all); vm.pollDownloads() },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White),
                    ) {
                        Text("Baixar todos os países (~625 MB)", fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = PanelBorder)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_fg),
                        contentDescription = null,
                        modifier = Modifier.height(26.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("MotoRota · Grupo Conecta", color = Color(0xFF22D3EE), fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                }
                Text(
                    "Dados © colaboradores do OpenStreetMap · motor BRouter.",
                    color = TextDim,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
fun HazardDialog(vm: AppViewModel, p: GeoPoint, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogBg,
        title = { Text("Reportar alerta na via", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HazardType.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { t ->
                            Button(
                                onClick = { vm.addHazard(t, p); onDismiss() },
                                modifier = Modifier.weight(1f).height(68.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceCard, contentColor = Color.White),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(t.emoji, fontSize = 22.sp)
                                    Spacer(Modifier.height(2.dp))
                                    Text(t.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun TripsDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogBg,
        title = { Text("Meus passeios salvos", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (vm.trips.isEmpty()) {
                    Text(
                        "Nenhum passeio salvo ainda. Monte uma rota com paradas e toque em \"Salvar passeio\".",
                        color = TextDim,
                        fontSize = 14.sp,
                    )
                }
                vm.trips.forEach { t ->
                    Surface(
                        color = SurfaceCard,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp)),
                            ) {
                                TextButton(
                                    onClick = { vm.loadTrip(t); onDismiss() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(
                                            t.name,
                                            fontSize = 15.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${t.stops.size} parada(s) · ${t.destination.name}",
                                            fontSize = 12.sp,
                                            color = TextDim,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { vm.deleteTrip(t.id) }) {
                                Icon(Icons.Rounded.DeleteOutline, "Excluir passeio", tint = TextDim)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

@Composable
fun SaveTripDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(vm.destination?.name ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogBg,
        title = { Text("Salvar passeio", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nome do roteiro") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { vm.saveTrip(name); onDismiss() }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun StopActionDialog(vm: AppViewModel, i: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogBg,
        title = { Text("Parada ${i + 1}", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionRowButton(icon = Icons.Rounded.OpenWith, text = "Mover este ponto") {
                    vm.movingStop = i; onDismiss()
                }
                ActionRowButton(
                    icon = Icons.Rounded.DeleteOutline,
                    text = "Remover este ponto",
                    containerColor = Color(0xFF7F1D1D),
                    iconTint = Color(0xFFFCA5A5),
                ) {
                    vm.removeStop(i); onDismiss()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

/** Resumo exibido ao concluir ou encerrar uma viagem. */
@Composable
fun TripSummaryDialog(s: AppViewModel.TripSummary, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogBg,
        title = {
            Text(
                if (s.arrived) "Chegamos ao destino!" else "Resumo da viagem",
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Surface(
                color = SurfaceCard,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryMetricRow(Icons.Rounded.Route, "Distância percorrida", formatDistance(s.distM))
                    SummaryMetricRow(Icons.Rounded.Schedule, "Tempo total", formatDuration(s.timeS.toInt()))
                    SummaryMetricRow(Icons.Rounded.SportsMotorsports, "Em movimento", formatDuration(s.movingS.toInt()))
                    SummaryMetricRow(Icons.Rounded.Speed, "Velocidade média", "${s.avgKmh} km/h")
                    SummaryMetricRow(Icons.AutoMirrored.Rounded.TrendingUp, "Velocidade máxima", "${s.maxKmh} km/h")
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun SummaryMetricRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = TextDim, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** Ajustes gerais: autonomia do tanque e proteção em áreas de risco conhecidas. */
@Composable
fun SettingsDialog(vm: AppViewModel, withMic: (() -> Unit) -> Unit = { it() }, onDismiss: () -> Unit) {
    var tankText by remember { mutableStateOf(if (vm.tankKm > 0) vm.tankKm.toString() else "") }
    var geminiText by remember { mutableStateOf(Keys.geminiSaved()) }
    var groqText by remember { mutableStateOf(Keys.groqSaved()) }
    var tomtomText by remember { mutableStateOf(Keys.tomtomSaved()) }
    var keysSaved by remember { mutableStateOf(false) }
    var nameText by remember { mutableStateOf(vm.intercom.myName) }
    var friendCode by remember { mutableStateOf("") }
    var friendNick by remember { mutableStateOf("") }
    var icInfo by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testReport by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogBg,
        title = { Text("Ajustes", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.LocalGasStation, null, tint = Color(0xFF60A5FA), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Autonomia do tanque", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            "Informe quantos km sua moto faz por tanque para acompanhar o consumo pelo GPS e receber alertas de reserva.",
                            color = TextDim,
                            fontSize = 12.5.sp,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = tankText,
                                onValueChange = { t -> tankText = t.filter { it.isDigit() }.take(4) },
                                label = { Text("km por tanque") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { vm.setTank(tankText.toIntOrNull() ?: 0) }) { Text("Salvar") }
                        }
                        if (vm.tankKm > 0) {
                            Text(
                                "Rodados: ${vm.kmSinceFill.toInt()} km · Restam ~${(vm.fuelLeftKm ?: 0).coerceAtLeast(0)} km",
                                color = TextDim,
                                fontSize = 12.5.sp,
                            )
                            Button(
                                onClick = { vm.refuel() },
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = NavGreen, contentColor = Color(0xFF042617)),
                            ) {
                                Text("Abasteci (zerar hodômetro)", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Jarvis nos seus aplicativos", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Peça por voz: \"abre o Spotify\", \"toca rock\", \"liga pra minha mãe\", " +
                                "\"manda no WhatsApp pro André que tô chegando\", \"lê minhas mensagens\".",
                            color = TextDim,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Avisar quando chegar mensagem (só diz de quem)", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Switch(checked = vm.readMessagesAloud, onCheckedChange = { vm.toggleReadMessages() })
                        }
                        Text(
                            "Se o Android disser \"Acesso negado\" / \"configuração restrita\", toque aqui primeiro: o MotoRota se reinstala " +
                                "pelo instalador oficial e o bloqueio some (seus dados continuam).",
                            color = TextDim,
                            fontSize = 12.sp,
                        )
                        Button(
                            onClick = { vm.installUpdate(null) },
                            enabled = vm.installProgress == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(vm.installProgress?.let { "Baixando… $it%" } ?: "Liberar bloqueio (reinstalar pelo MotoRota)")
                        }
                        val notifOk = vm.phone.notificationsAllowed()
                        OutlinedButton(onClick = { vm.phone.openNotificationSettings() }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (notifOk) "Leitura de mensagens: ativada ✓" else "1. Ativar leitura de mensagens")
                        }
                        val accOk = com.motorota.assistant.WhatsAppSender.enabled(LocalContext.current)
                        OutlinedButton(onClick = { vm.phone.openAccessibilitySettings() }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (accOk) "Envio automático no WhatsApp: ativado ✓" else "2. Ativar envio automático no WhatsApp")
                        }
                        Text(
                            "No 2, procure \"MotoRota: Jarvis envia WhatsApp\" em Acessibilidade (às vezes em Apps instalados) e ative. " +
                                "Ele só toca em Enviar logo depois de você pedir uma mensagem.",
                            color = TextDim,
                            fontSize = 12.sp,
                        )
                    }
                }

                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val ic = vm.intercom
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Headset, null, tint = NavGreen, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Intercomunicador", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(
                                if (ic.online) "● online" else "● offline",
                                color = if (ic.online) NavGreen else TextDim,
                                fontSize = 12.5.sp,
                            )
                        }
                        Text(
                            "Fale com amigos que usam o MotoRota pela internet: \"Jarvis, chama o André\". " +
                                "O Jarvis dele pergunta se pode atender. Funciona com o app aberto.",
                            color = TextDim,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = nameText,
                                onValueChange = { nameText = it.take(40) },
                                label = { Text("Seu nome") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { scope.launch { icInfo = ic.setName(nameText) } }) { Text("Salvar") }
                        }
                        if (ic.myCode.isNotBlank()) {
                            Text("Seu código: ${ic.myCode}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("Passe esse código para os amigos te adicionarem.", color = TextDim, fontSize = 12.sp)
                        }
                        HorizontalDivider(color = PanelBorder)
                        Text("Adicionar amigo", color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = friendCode,
                                onValueChange = { t -> friendCode = t.filter { it.isDigit() }.take(6) },
                                label = { Text("Código") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = friendNick,
                                onValueChange = { friendNick = it.take(30) },
                                label = { Text("Como chamar") },
                                singleLine = true,
                                modifier = Modifier.weight(1.3f),
                            )
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    icInfo = ic.addFriend(friendCode, friendNick)
                                    if (icInfo.contains("adicionado")) { friendCode = ""; friendNick = "" }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Adicionar") }
                        if (icInfo.isNotBlank()) Text(icInfo, color = Color(0xFF93C5FD), fontSize = 12.5.sp)
                        ic.friends.forEach { f ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(f.name, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                IconButton(onClick = { withMic { vm.message = ic.call(f) } }) {
                                    Icon(Icons.Rounded.Call, "Chamar ${f.name}", tint = NavGreen)
                                }
                                IconButton(onClick = { ic.removeFriend(f.id) }) {
                                    Icon(Icons.Rounded.DeleteOutline, "Remover ${f.name}", tint = TextDim)
                                }
                            }
                        }
                    }
                }

                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Mic, null, tint = Color(0xFF22D3EE), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Assistente Jarvis", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            "Diga \"Jarvis\" (ou toque no microfone) e peça: \"programa a rota pra Xerém\", " +
                                "\"quantos radares tem pra frente?\", \"quanto falta?\", \"posto mais perto\"... " +
                                "Ele também conversa sobre qualquer assunto.",
                            color = TextDim,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Atender quando eu disser \"Jarvis\"", color = Color.White, fontSize = 14.5.sp)
                                Text(
                                    "Ouve só com o app aberto na tela. Na 1ª vez baixa 40 MB (use Wi-Fi).",
                                    color = TextDim,
                                    fontSize = 12.sp,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = vm.jarvis.wakeOn,
                                onCheckedChange = { on -> if (on) withMic { vm.jarvis.setWake(true) } else vm.jarvis.setWake(false) },
                            )
                        }
                        Text("Voz do Jarvis (toque para ouvir)", color = Color.White, fontSize = 14.5.sp)
                        val voices = PiperVoice.VOICES.map { "piper:${it.first}" to "${it.second} · sem limite, offline" } +
                            JarvisVoice.VOICES.map { it.first to "${it.second} · Google, poucas por dia" } +
                            ("celular" to "Voz do celular (robótica)")
                        vm.voiceDownload?.let { p ->
                            Text("Baixando a voz… $p%", color = Color(0xFF93C5FD), fontSize = 12.5.sp)
                        }
                        voices.forEach { (id, label) ->
                            val sel = vm.jarvisVoice == id
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) Color(0x3322D3EE) else Color.Transparent)
                                    .clickable {
                                        vm.setJarvisVoiceName(id)
                                        scope.launch { vm.speakAndWait("Olá, eu sou o Jarvis. Em que posso ajudar?") }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (sel) "●" else "○", color = Color(0xFF22D3EE), fontSize = 16.sp)
                                Spacer(Modifier.width(10.dp))
                                Text(label, color = Color.White, fontSize = 14.sp)
                            }
                        }
                        OutlinedTextField(
                            value = groqText,
                            onValueChange = { groqText = it.trim(); keysSaved = false },
                            label = { Text(if (Keys.groqBuiltIn) "Chave do Groq (já configurada)" else "Chave do Groq (recomendada: rápida e grátis)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Grátis em console.groq.com/keys. O Jarvis usa o Groq primeiro e o Gemini de reserva.",
                            color = TextDim,
                            fontSize = 12.sp,
                        )
                        OutlinedTextField(
                            value = geminiText,
                            onValueChange = { geminiText = it.trim(); keysSaved = false },
                            label = { Text(if (Keys.geminiBuiltIn) "Chave do Gemini (já configurada)" else "Chave do Gemini (obrigatória)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Grátis em aistudio.google.com/apikey (entre com sua conta Google e toque em \"Create API key\").",
                            color = TextDim,
                            fontSize = 12.sp,
                        )
                        OutlinedTextField(
                            value = tomtomText,
                            onValueChange = { tomtomText = it.trim(); keysSaved = false },
                            label = { Text(if (Keys.tomtomBuiltIn) "Chave da TomTom (já configurada)" else "Chave da TomTom (busca e trânsito)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Opcional, mas melhora muito a busca de hotéis, praças e lojas. Grátis em developer.tomtom.com.",
                            color = TextDim,
                            fontSize = 12.sp,
                        )
                        Button(
                            onClick = { Keys.save(tomtomText, geminiText); Keys.saveGroq(groqText); keysSaved = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (keysSaved) "Chaves salvas ✓" else "Salvar chaves") }
                        OutlinedButton(
                            onClick = {
                                testing = true
                                testReport = "Testando…"
                                scope.launch {
                                    testReport = vm.jarvis.diagnose()
                                    testing = false
                                }
                            },
                            enabled = !testing,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Testar o Jarvis (conexão com o Gemini)") }
                        if (testReport.isNotBlank()) {
                            Text(testReport, color = Color(0xFF93C5FD), fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }

                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Shield, null, tint = NavGreen, modifier = Modifier.size(19.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Áreas de risco conhecidas", color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(4.dp))
                            val n = KnownRisks.all
                            Text(
                                "Desvia de ruas internas em ${n.count { it.avoid }} áreas críticas, monitora " +
                                    "${Corridors.all.size} corredores expressos e prioriza contornos rodoviários seguros " +
                                    "(com peso reforçado à noite e fins de semana).",
                                color = TextDim,
                                fontSize = 12.5.sp,
                                lineHeight = 17.sp,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = vm.knownRisksOn, onCheckedChange = { vm.toggleKnownRisks() })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar", fontSize = 15.sp) } },
    )
}

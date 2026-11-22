package com.motorota.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorota.AppViewModel
import com.motorota.data.MapPacks
import com.motorota.routing.GeoPoint
import com.motorota.routing.Waypoint
import java.util.Locale

@Composable
private fun BigButton(text: String, color: Color = Color(0xFF2A3036), textColor: Color = Color.White, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(58.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = textColor),
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun LongPressDialog(vm: AppViewModel, p: GeoPoint, onReport: () -> Unit, onDismiss: () -> Unit) {
    var askRadius by remember { mutableStateOf(false) }
    val near = vm.riskNear(p)
    val name = String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1E22),
        title = { Text(if (askRadius) "Tamanho da área de risco" else "O que fazer aqui?", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!askRadius) {
                    BigButton("🏁  Ir para cá", color = Color(0xFF26C281), textColor = Color.Black) {
                        vm.goTo(Waypoint(p, "Ponto no mapa")); onDismiss()
                    }
                    if (vm.destination != null) BigButton("📍  Passar por aqui") {
                        vm.addViaSmart(p); onDismiss()
                    }
                    BigButton("🚩  Sair daqui (partida)") {
                        vm.setOriginHere(Waypoint(p, "Partida: $name")); onDismiss()
                    }
                    BigButton("🕳️  Avisar: buraco, polícia, obra…", color = Color(0xFFE65100)) { onDismiss(); onReport() }
                    BigButton("⛔  Marcar área de risco", color = Danger) { askRadius = true }
                    vm.hazardNear(p)?.let { h ->
                        BigButton("🗑  Remover aviso: ${h.type.label}") { vm.removeHazard(h.id); onDismiss() }
                    }
                    if (near != null) BigButton("🗑  Remover esta área de risco") {
                        vm.removeRisk(near.id); onDismiss()
                    }
                } else {
                    Text("A rota nunca vai passar por dentro dessa área.", color = Color(0xFF9AA3AB))
                    listOf(300 to "Pequena — 300 m (uma rua, um trecho)",
                        800 to "Média — 800 m (um bairro pequeno)",
                        2000 to "Grande — 2 km (uma região)").forEach { (r, label) ->
                        BigButton(label) { vm.addRisk(p, r, "Área de risco"); onDismiss() }
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
    @Suppress("UNUSED_VARIABLE") val refresh = vm.mapsVersion // recompõe quando um mapa termina

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1E22),
        title = { Text("Mapas de rota (offline)", color = Color.White) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Baixe uma vez (de preferência no Wi-Fi). Depois as rotas são calculadas sem internet. " +
                        "O download continua mesmo se você sair do app.",
                    color = Color(0xFF9AA3AB), fontSize = 14.sp,
                )
                if (prog.pending > 0) {
                    val frac = if (prog.bytesTotal > 0) prog.bytesDone.toFloat() / prog.bytesTotal else 0f
                    Text("Baixando ${prog.pending} arquivo(s)… ${prog.bytesDone / 1_048_576} MB", color = Amber)
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                }
                MapPacks.countries.forEach { c ->
                    val have = vm.downloader.installedCount(c)
                    val complete = have == c.tiles.size
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${c.flag} ${c.name}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (complete) "✅ instalado" else "${c.sizeMb} MB · $have/${c.tiles.size} partes",
                                color = Color(0xFF9AA3AB), fontSize = 13.sp,
                            )
                        }
                        if (!complete) OutlinedButton(onClick = { vm.downloader.download(c.tiles); vm.pollDownloads() }) {
                            Text("Baixar")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                val all = MapPacks.countries.flatMap { it.tiles }.distinct()
                if (all.any { !vm.downloader.isInstalled(it) }) {
                    BigButton("⬇  Baixar os 4 países (~625 MB)", color = Blue, textColor = Color.White) {
                        vm.downloader.download(all); vm.pollDownloads()
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(com.motorota.R.drawable.ic_launcher_fg),
                        contentDescription = null,
                        modifier = Modifier.height(28.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("  MotoRota · um app do Grupo Conecta", color = Color(0xFF22D3EE), fontSize = 13.sp)
                }
                Text(
                    "Dados: © colaboradores do OpenStreetMap, processados pelo BRouter (brouter.de).",
                    color = Color(0xFF6C757D), fontSize = 11.sp,
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
        containerColor = Color(0xFF1A1E22),
        title = { Text("Avisar o quê?", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.motorota.data.HazardType.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { t ->
                            Button(
                                onClick = { vm.addHazard(t, p); onDismiss() },
                                modifier = Modifier.weight(1f).height(72.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3036), contentColor = Color.White),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(t.emoji, fontSize = 24.sp)
                                    Text(t.label, fontSize = 13.sp, maxLines = 1)
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
        containerColor = Color(0xFF1A1E22),
        title = { Text("⭐ Meus passeios", color = Color.White) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.trips.isEmpty()) {
                    Text(
                        "Nenhum passeio salvo ainda. Monte uma rota com as paradas que quiser e toque em \"💾 Salvar passeio\".",
                        color = Color(0xFF9AA3AB),
                    )
                }
                vm.trips.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { vm.loadTrip(t); onDismiss() },
                            modifier = Modifier.weight(1f).height(64.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3036), contentColor = Color.White),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(t.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(
                                    "${t.stops.size} parada(s) · ${t.destination.name}",
                                    fontSize = 12.sp, color = Color(0xFF9AA3AB), maxLines = 1,
                                )
                            }
                        }
                        TextButton(onClick = { vm.deleteTrip(t.id) }) { Text("🗑") }
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
        containerColor = Color(0xFF1A1E22),
        title = { Text("💾 Salvar passeio", color = Color.White) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nome do passeio") },
                singleLine = true,
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
        containerColor = Color(0xFF1A1E22),
        title = { Text("Ponto ${i + 1}", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("✋  Mover este ponto") { vm.movingStop = i; onDismiss() }
                BigButton("🗑  Remover este ponto", color = Danger) { vm.removeStop(i); onDismiss() }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

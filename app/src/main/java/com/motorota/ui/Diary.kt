package com.motorota.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.motorota.data.RiddenTrip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BR = Locale("pt", "BR")

/** Menu > Diário de viagens: tudo que foi rodado, com nome, anotação e traçado no mapa. */
@Composable
fun DiarySection(vm: AppViewModel, onShowOnMap: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (vm.ridden.isEmpty()) {
            Text(
                "Nenhuma viagem ainda. Toda vez que você navega até um destino, a viagem fica salva aqui " +
                    "com distância, tempo, velocidades, combustível e o caminho que fez.",
                color = TextDim, fontSize = 14.sp,
            )
        } else {
            val km = vm.ridden.sumOf { it.distM } / 1000
            Text(
                String.format(BR, "%d viagens · %.0f km rodados no total", vm.ridden.size, km),
                color = TextDim, fontSize = 13.sp,
            )
        }
        vm.ridden.forEach { t -> TripCard(vm, t, onShowOnMap) }
    }
}

@Composable
private fun TripCard(vm: AppViewModel, t: RiddenTrip, onShowOnMap: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var name by remember(t.name) { mutableStateOf(t.name) }
    var note by remember(t.note) { mutableStateOf(t.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(
        color = SurfaceCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)).clickable { open = !open },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t.name, color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            Text(
                SimpleDateFormat("dd/MM/yyyy HH:mm", BR).format(Date(t.startedAt)) + " · " +
                    formatDistance(t.distM) + " · " + formatDuration(t.timeS.toInt()),
                color = TextDim, fontSize = 12.5.sp,
            )
            if (open) {
                Text(
                    "Média ${t.avgKmh} km/h · máxima ${t.maxKmh} km/h · em movimento ${formatDuration(t.movingS.toInt())}" +
                        (if (t.liters > 0) String.format(BR, " · %.1f L", t.liters) else "") +
                        (if (t.cost > 0) String.format(BR, " · R$ %.0f", t.cost) else "") +
                        (if (t.bike.isNotBlank()) " · ${t.bike}" else "") +
                        (if (!t.arrived) " · encerrada antes do destino" else ""),
                    color = Color(0xFFCBD5E1), fontSize = 13.sp, lineHeight = 18.sp,
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(60) },
                    label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(300) },
                    label = { Text("Anotações (onde parou, o que achou...)") }, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.renameRidden(t.id, name, note); open = false }) { Text("Salvar") }
                    OutlinedButton(onClick = { vm.showRidden(t.id); onShowOnMap() }) { Text("Ver no mapa") }
                    TextButton(onClick = {
                        if (confirmDelete) vm.deleteRidden(t.id) else confirmDelete = true
                    }) { Text(if (confirmDelete) "Confirmar" else "Apagar", color = Danger) }
                }
            }
        }
    }
}

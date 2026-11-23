package com.motorota.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorota.AppViewModel
import com.motorota.routing.formatDistance
import com.motorota.routing.formatDuration
import kotlin.math.cos
import kotlin.math.sin

private val GaugeTrack = Color(0xFF2A3036)
private val GaugeBlue = Color(0xFF4C8DF6)
private val GaugeWarn = Color(0xFFFFA000)

/**
 * Velocimetro de moto: anel de 240 graus que enche com a velocidade.
 * Fica laranja perto do limite e vermelho acima dele; o limite aparece como um tracinho no anel.
 */
@Composable
fun SpeedGauge(speedKmh: Int, limitKmh: Int?, modifier: Modifier = Modifier) {
    val maxScale = when {
        (limitKmh ?: 0) > 100 || speedKmh > 110 -> 180f
        else -> 140f
    }
    val anim by animateFloatAsState(speedKmh.toFloat().coerceIn(0f, maxScale), tween(600), label = "vel")
    val over = limitKmh != null && speedKmh > limitKmh + 5
    val near = limitKmh != null && !over && speedKmh > limitKmh - 3
    val color = when {
        over -> Danger
        near -> GaugeWarn
        else -> GaugeBlue
    }
    Box(modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val stroke = 13.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val tl = Offset(inset, inset)
            val start = 150f
            val sweep = 240f
            drawArc(GaugeTrack, start, sweep, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, start, sweep * (anim / maxScale), false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            // marca do limite da via
            if (limitKmh != null && limitKmh in 1..maxScale.toInt()) {
                val ang = Math.toRadians((start + sweep * limitKmh / maxScale).toDouble())
                val r = arcSize.width / 2
                val c = Offset(size.width / 2, size.height / 2)
                val p1 = Offset(c.x + (r - stroke) * cos(ang).toFloat(), c.y + (r - stroke) * sin(ang).toFloat())
                val p2 = Offset(c.x + (r + stroke / 1.2f) * cos(ang).toFloat(), c.y + (r + stroke / 1.2f) * sin(ang).toFloat())
                drawLine(Color.White, p1, p2, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$speedKmh", fontSize = 40.sp, fontWeight = FontWeight.Black,
                color = if (over) Danger else Color.White, maxLines = 1, softWrap = false,
            )
            Text("km/h", fontSize = 12.sp, color = TextDim)
        }
    }
}

/** "chip" pequeno do computador de bordo (ex.: ⛽ 142 km) */
@Composable
fun CockpitChip(icon: String, value: String, highlight: Color? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(highlight ?: Color(0xFF242A30))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 13.sp)
        Spacer(Modifier.width(4.dp))
        Text(value, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Resumo mostrado ao chegar (ou ao sair da navegacao) */
@Composable
fun TripSummaryDialog(s: AppViewModel.TripSummary, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1E22),
        title = {
            Text(
                if (s.arrived) "🏁 Chegamos!" else "Resumo da viagem",
                color = Color.White, fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryLine("🛣️", "Distância", formatDistance(s.distM))
                SummaryLine("⏱️", "Tempo total", formatDuration(s.timeS.toInt()))
                SummaryLine("🏍️", "Em movimento", formatDuration(s.movingS.toInt()))
                SummaryLine("📊", "Média", "${s.avgKmh} km/h")
                SummaryLine("🚀", "Máxima", "${s.maxKmh} km/h")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", fontSize = 18.sp) } },
    )
}

@Composable
private fun SummaryLine(icon: String, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Text(label, color = TextDim, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** Ajustes: autonomia do tanque e areas de risco conhecidas */
@Composable
fun SettingsDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var tankText by remember { mutableStateOf(if (vm.tankKm > 0) vm.tankKm.toString() else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1E22),
        title = { Text("⚙️ Ajustes da moto", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("⛽ Autonomia do tanque", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Quantos km a moto roda com o tanque cheio. O app conta os km rodados e avisa quando estiver acabando.",
                    color = TextDim, fontSize = 13.sp,
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
                        "Rodados desde o último abastecimento: ${vm.kmSinceFill.toInt()} km · restam ~${vm.fuelLeftKm ?: 0} km",
                        color = TextDim, fontSize = 13.sp,
                    )
                    Button(
                        onClick = { vm.refuel() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NavGreen, contentColor = Color.White),
                    ) { Text("⛽ Abasteci (tanque cheio)", fontSize = 16.sp) }
                }

                HorizontalDivider(color = PanelBorder)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("🚨 Áreas de risco conhecidas", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        val n = com.motorota.data.KnownRisks.all
                        Text(
                            "${n.count { it.avoid }} comunidades para evitar, ${n.count { !it.avoid }} pontos de atenção, " +
                                "${com.motorota.data.Corridors.all.size} rodovias com muitos assaltos (só aviso por voz) e " +
                                "${com.motorota.data.Corridors.zones.count { it.dense }} áreas urbanas densas. " +
                                "Regra: rodovia que passa por fora é melhor que rodovia dentro da cidade, " +
                                "que é melhor que rua de bairro. À noite e no fim de semana o app foge mais da área urbana.",
                            color = TextDim, fontSize = 13.sp,
                        )
                    }
                    Switch(checked = vm.knownRisksOn, onCheckedChange = { vm.toggleKnownRisks() })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar", fontSize = 16.sp) } },
    )
}

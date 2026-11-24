package com.motorota.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GpsOff
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorota.AppViewModel
import com.motorota.nav.Instructions
import com.motorota.routing.formatDistance
import com.motorota.routing.formatDuration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

private val GaugeTrack = Color(0xFF242C37)
private val GaugeBlue = Color(0xFF3B82F6)
private val GaugeWarn = Color(0xFFF59E0B)

/**
 * Velocímetro de moto: anel de 240° que preenche conforme a velocidade.
 * Laranja próximo ao limite e vermelho acima; traço branco marca o limite da via.
 */
@Composable
fun SpeedGauge(speedKmh: Int, limitKmh: Int?, modifier: Modifier = Modifier) {
    val maxScale = if ((limitKmh ?: 0) > 100 || speedKmh > 110) 180f else 140f
    val anim by animateFloatAsState(speedKmh.toFloat().coerceIn(0f, maxScale), tween(450), label = "vel")
    val over = limitKmh != null && speedKmh > limitKmh + 5
    val near = limitKmh != null && !over && speedKmh > limitKmh - 3
    val color = when {
        over -> Danger
        near -> GaugeWarn
        else -> GaugeBlue
    }
    Box(modifier.size(116.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(5.dp)) {
            val stroke = 11.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val tl = Offset(inset, inset)
            val start = 150f
            val sweep = 240f
            drawArc(GaugeTrack, start, sweep, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, start, sweep * (anim / maxScale), false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))

            if (limitKmh != null && limitKmh in 1..maxScale.toInt()) {
                val ang = Math.toRadians((start + sweep * limitKmh / maxScale).toDouble())
                val r = arcSize.width / 2
                val c = Offset(size.width / 2, size.height / 2)
                val p1 = Offset(c.x + (r - stroke * 0.9f) * cos(ang).toFloat(), c.y + (r - stroke * 0.9f) * sin(ang).toFloat())
                val p2 = Offset(c.x + (r + stroke * 0.75f) * cos(ang).toFloat(), c.y + (r + stroke * 0.75f) * sin(ang).toFloat())
                drawLine(Color.White, p1, p2, strokeWidth = 3.5.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$speedKmh",
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                color = if (over) Danger else Color.White,
                maxLines = 1,
                softWrap = false,
            )
            Text("km/h", fontSize = 11.sp, color = TextDim, fontWeight = FontWeight.Medium)
        }
    }
}

/** Placa brasileira R-19 de limite de velocidade ("--" quando a via não informa). */
@Composable
fun SpeedLimitSign(limit: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(68.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
                .border(6.dp, if (limit != null) Color(0xFFD32F2F) else Color(0xFF94A3B8), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                limit?.toString() ?: "--",
                fontSize = if ((limit ?: 0) >= 100) 22.sp else 25.sp,
                fontWeight = FontWeight.Black,
                color = if (limit != null) Color(0xFF111827) else Color(0xFF94A3B8),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "LIMITE",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier
                .background(Color(0xB30E1116), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** Pílula compacta do computador de bordo (distância, média, combustível). */
@Composable
fun CockpitChip(icon: ImageVector, value: String, highlight: Color? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(highlight ?: SurfaceCard)
            .border(1.dp, if (highlight != null) WarningColor.copy(alpha = 0.5f) else PanelBorder, RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (highlight != null) Color.White else TextDim, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(value, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** HUD completo durante a navegação ativa. */
@Composable
fun NavigationHud(
    vm: AppViewModel,
    onAction: (QuickAction) -> Unit,
    onMenu: () -> Unit,
) {
    val st = vm.navState
    val navBg = Color(0xF511161D)
    val limit = vm.currentLimit
        ?: vm.nextAlert?.takeIf { it.first.isRadar && it.second < 400 }?.first?.limitKmh?.takeIf { it > 0 }

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // ---- Topo: Manobra principal + "Em seguida" + Alerta à frente ----
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = navBg,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(10.dp, RoundedCornerShape(20.dp))
                    .border(1.dp, PanelBorder, RoundedCornerShape(20.dp)),
            ) {
                Column {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(62.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Blue.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            ManeuverIcon(st?.nextHint, size = 44.dp, tint = Color(0xFF60A5FA))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            when {
                                vm.rerouting -> {
                                    Text("Recalculando rota…", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = WarningColor)
                                    Text("Buscando melhor caminho a partir daqui", fontSize = 14.sp, color = TextDim)
                                }
                                st == null -> {
                                    Text("Aguardando GPS…", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("Posicione-se em local aberto", fontSize = 14.sp, color = TextDim)
                                }
                                else -> {
                                    Text(
                                        formatDistance(st.distToHintM),
                                        fontSize = 36.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White,
                                    )
                                    Text(
                                        st.nextHint?.let { Instructions.text(it).replaceFirstChar { c -> c.uppercase() } }
                                            ?: "Siga até o destino",
                                        fontSize = 19.sp,
                                        color = Color(0xFF93C5FD),
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }

                    // "Em seguida" quando a próxima manobra está a menos de 800 m da atual
                    val r = vm.currentRoute
                    val cur = st?.nextHint
                    val after = if (r != null && cur != null) r.hints.firstOrNull { it.index > cur.index } else null
                    if (r != null && cur != null && after != null) {
                        val gap = r.cumDist[after.index.coerceAtMost(r.cumDist.size - 1)] -
                            r.cumDist[cur.index.coerceAtMost(r.cumDist.size - 1)]
                        if (gap < 800) {
                            HorizontalDivider(color = PanelBorder)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color(0x40000000))
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Em seguida", fontSize = 13.sp, color = TextDim, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(10.dp))
                                ManeuverIcon(after, size = 22.dp, tint = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    Instructions.text(after).replaceFirstChar { it.uppercase() },
                                    fontSize = 14.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // Aviso de perda temporária de sinal GPS (ex.: túnel)
            AnimatedVisibility(visible = vm.gpsLost, enter = fadeIn(), exit = fadeOut()) {
                Surface(
                    color = Color(0xEB78350F),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.GpsOff, contentDescription = null, tint = Color(0xFFFDE68A), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sinal de GPS fraco ou em túnel", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Alerta ou radar próximo na rota
            val alertPair = vm.nextAlert
            AnimatedVisibility(
                visible = alertPair != null,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut(),
            ) {
                alertPair?.let { (a, d) ->
                    Surface(
                        color = if (a.isRadar) Color(0xF2991B1B) else Color(0xF2B45309),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.shadow(6.dp, RoundedCornerShape(14.dp)),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(a.emoji, fontSize = 24.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${a.title}  ·  ${formatDistance(d)}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // ---- Base: Placa de limite + Atalhos fixados + Cockpit inferior ----
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                SpeedLimitSign(limit)
                Spacer(Modifier.weight(1f))
                PinnedButtons(vm, nav = true, maxVisible = 3, buttonSize = 50.dp, onAction = onAction)
            }

            Surface(
                color = navBg,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, PanelBorder, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
            ) {
                Row(
                    Modifier.padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SpeedGauge(vm.speedKmh, limit)
                    Spacer(Modifier.width(6.dp))

                    Column(Modifier.weight(1f)) {
                        val resumo = st?.let { "${formatDuration(it.remainingS)}  •  ${formatDistance(it.remainingM)}" } ?: ""
                        if (!vm.following) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Blue.copy(alpha = 0.2f))
                                    .border(1.dp, Blue, RoundedCornerShape(14.dp))
                                    .clickable { vm.recenter() }
                                    .padding(vertical = 8.dp, horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(38.dp).clip(CircleShape).background(Blue),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.MyLocation, "Re-centralizar", tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text("Re-centralizar", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                                    if (resumo.isNotEmpty()) Text(resumo, fontSize = 13.sp, color = TextDim, maxLines = 1)
                                }
                            }
                        } else if (st != null) {
                            val eta = LocalTime.now().plusSeconds(st.remainingS.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(eta, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text("chegada", fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(bottom = 4.dp))
                            }
                            Text(resumo, fontSize = 14.sp, color = TextDim, fontWeight = FontWeight.Medium, maxLines = 1)
                        }

                        if (vm.stops.isNotEmpty()) {
                            Text(
                                "Parada: ${vm.stops.first().name}",
                                fontSize = 12.sp,
                                color = Color(0xFF60A5FA),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CockpitChip(Icons.Rounded.Route, formatDistance(vm.tripDistM))
                            if (vm.tripAvgKmh > 0) CockpitChip(Icons.Rounded.Speed, "${vm.tripAvgKmh} km/h")
                            vm.fuelLeftKm?.let { left ->
                                CockpitChip(
                                    Icons.Rounded.LocalGasStation,
                                    "${left.coerceAtLeast(0)} km",
                                    highlight = if (left <= 60) Color(0xFF92400E) else null,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(6.dp))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (vm.following) {
                            FilledTonalButton(
                                onClick = { vm.overview() },
                                modifier = Modifier.height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = SurfaceCard,
                                    contentColor = Color(0xFF93C5FD),
                                ),
                            ) { Text("Geral", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                        }
                        RoundButton(onClick = onMenu, size = 48.dp) {
                            Icon(Icons.Rounded.Menu, contentDescription = "Menu", tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

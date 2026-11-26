package com.motorota.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorota.AppViewModel

/**
 * Funções do app que podem ficar fixadas como atalho rápido na tela.
 * Mapa (planejamento) e navegação mantêm listas independentes.
 */
enum class QuickAction(val id: String, val label: String, val inMap: Boolean, val inNav: Boolean) {
    PASSEIOS("passeios", "Meus passeios", true, false),
    TEMA("tema", "Tema dia / noite", true, true),
    SATELITE("satelite", "Imagem de satélite", true, true),
    AVISAR("avisar", "Reportar alerta na via", true, true),
    VOZ("voz", "Avisos por voz", true, true),
    ABASTECI("abasteci", "Registrar abastecimento", true, true),
    GERAL("geral", "Visão geral da rota", false, true),
    MAPAS("mapas", "Mapas offline", true, false),
    AJUSTES("ajustes", "Ajustes (moto e Jarvis)", true, true),
    JARVIS("jarvis", "Falar com o Jarvis", true, true),
    SAIR("sair", "Encerrar navegação", false, true),
    ;

    fun available(nav: Boolean) = if (nav) inNav else inMap

    companion object {
        val DEFAULT_MAP = setOf(JARVIS.id)
        val DEFAULT_NAV = setOf(AVISAR.id, JARVIS.id)
    }
}

fun QuickAction.vectorIcon(vm: AppViewModel): ImageVector = when (this) {
    QuickAction.PASSEIOS -> Icons.Rounded.Star
    QuickAction.TEMA -> if (vm.night) Icons.Rounded.LightMode else Icons.Rounded.DarkMode
    QuickAction.SATELITE -> Icons.Rounded.SatelliteAlt
    QuickAction.AVISAR -> Icons.Rounded.WarningAmber
    QuickAction.VOZ -> if (vm.voiceOn) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff
    QuickAction.ABASTECI -> Icons.Rounded.LocalGasStation
    QuickAction.GERAL -> Icons.Rounded.ZoomOutMap
    QuickAction.MAPAS -> Icons.Rounded.Download
    QuickAction.AJUSTES -> Icons.Rounded.Tune
    QuickAction.JARVIS -> Icons.Rounded.Mic
    QuickAction.SAIR -> Icons.Rounded.Close
}

fun QuickAction.iconTint(vm: AppViewModel): Color = when (this) {
    QuickAction.AVISAR -> WarningColor
    QuickAction.SAIR -> Danger
    QuickAction.JARVIS -> if (vm.jarvis.wakeOn) Color(0xFF22D3EE) else Color.White
    QuickAction.VOZ -> if (vm.voiceOn) Color.White else TextDim
    QuickAction.TEMA -> if (vm.night) Color(0xFFFDE047) else Color.White
    else -> Color.White
}

/** Coluna de atalhos fixados na lateral direita, limitada para nunca estourar a tela. */
@Composable
fun PinnedButtons(
    vm: AppViewModel,
    nav: Boolean,
    maxVisible: Int = 4,
    buttonSize: Dp = 50.dp,
    onAction: (QuickAction) -> Unit,
) {
    val pinned = vm.pinned(nav)
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickAction.entries
            .filter { it.available(nav) && it.id in pinned }
            .take(maxVisible)
            .forEach { a ->
                val active = a == QuickAction.SATELITE && vm.satellite
                RoundButton(
                    onClick = { onAction(a) },
                    highlight = active,
                    size = buttonSize,
                ) {
                    Icon(
                        imageVector = a.vectorIcon(vm),
                        contentDescription = a.label,
                        tint = if (active) Color.White else a.iconTint(vm),
                        modifier = Modifier.size(23.dp),
                    )
                }
            }
    }
}

/** Menu completo com opção de fixar/desafixar cada ação na tela. */
@Composable
fun QuickMenuDialog(
    vm: AppViewModel,
    nav: Boolean,
    onAction: (QuickAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val pinned = vm.pinned(nav)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = {
            Column {
                Text("Menu rápido", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "Toque no alfinete para fixar atalhos " + if (nav) "na navegação." else "no mapa.",
                    color = TextDim,
                    fontSize = 13.sp,
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                QuickAction.entries
                    .filter { it.available(nav) }
                    .sortedBy { if (nav && it == QuickAction.SAIR) 0 else 1 }
                    .forEachIndexed { i, a ->
                        if (i > 0) HorizontalDivider(color = PanelBorder.copy(alpha = 0.6f))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Row(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onAction(a); onDismiss() }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = a.vectorIcon(vm),
                                    contentDescription = null,
                                    tint = a.iconTint(vm),
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text(
                                        a.label,
                                        fontSize = 16.sp,
                                        color = if (a == QuickAction.SAIR) Danger else Color.White,
                                        fontWeight = if (a == QuickAction.SAIR) FontWeight.Bold else FontWeight.Medium,
                                    )
                                    val sub = when (a) {
                                        QuickAction.VOZ -> if (vm.voiceOn) "Ativado" else "Silenciado"
                                        QuickAction.SATELITE -> if (vm.satellite) "Ativado" else "Mapa padrão"
                                        QuickAction.TEMA -> if (vm.night) "Modo noturno" else "Modo diurno"
                                        QuickAction.JARVIS -> if (vm.jarvis.wakeOn) "Atende quando você diz \"Jarvis\"" else "Toque para falar"
                                        else -> null
                                    }
                                    if (sub != null) Text(sub, fontSize = 12.sp, color = TextDim)
                                }
                            }
                            val on = a.id in pinned
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (on) Blue else SurfaceCard)
                                    .clickable { vm.togglePin(nav, a.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Rounded.PushPin,
                                    contentDescription = if (on) "Desafixar" else "Fixar",
                                    tint = if (on) Color.White else TextDim,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        }
                    }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar", fontSize = 15.sp) } },
    )
}

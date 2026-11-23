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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorota.AppViewModel

/**
 * Todas as funcoes do app que podem virar botao na tela.
 * Cada uma diz se faz sentido no mapa (planejando) e/ou na navegacao.
 */
enum class QuickAction(val id: String, val emoji: String, val label: String, val inMap: Boolean, val inNav: Boolean) {
    PASSEIOS("passeios", "⭐", "Meus passeios", true, false),
    TEMA("tema", "🌙", "Tema dia / noite", true, true),
    SATELITE("satelite", "🛰️", "Satélite", true, true),
    AVISAR("avisar", "⚠️", "Avisar (buraco, polícia…)", false, true),
    VOZ("voz", "🔊", "Voz liga / desliga", true, true),
    ABASTECI("abasteci", "⛽", "Abasteci", true, true),
    GERAL("geral", "🗺️", "Ver a rota toda (Geral)", false, true),
    MAPAS("mapas", "⬇️", "Baixar mapas", true, false),
    AJUSTES("ajustes", "⚙️", "Ajustes da moto", true, true),
    SAIR("sair", "🛑", "Sair da navegação", false, true),
    ;

    fun available(nav: Boolean) = if (nav) inNav else inMap

    companion object {
        /** botoes que ja vem na tela na primeira vez */
        val DEFAULT_MAP = setOf(PASSEIOS.id, TEMA.id, SATELITE.id)
        val DEFAULT_NAV = setOf(AVISAR.id, VOZ.id)
    }
}

/** Emoji que mostra o estado atual (ex.: tema mostra sol/lua, voz mostra mudo) */
fun QuickAction.icon(vm: AppViewModel): String = when (this) {
    QuickAction.TEMA -> if (vm.night) "☀️" else "🌙"
    QuickAction.VOZ -> if (vm.voiceOn) "🔊" else "🔇"
    else -> emoji
}

/** Botoes fixados na tela (coluna da direita) */
@Composable
fun PinnedButtons(vm: AppViewModel, nav: Boolean, onAction: (QuickAction) -> Unit) {
    val pinned = vm.pinned(nav)
    QuickAction.entries.filter { it.available(nav) && it.id in pinned }.forEach { a ->
        RoundButton(onClick = { onAction(a) }, highlight = a == QuickAction.SATELITE && vm.satellite) {
            Text(a.icon(vm), fontSize = 22.sp)
        }
    }
}

/**
 * Menu completo (botao ☰). Tocar no nome = usar a funcao.
 * Tocar no 📌 = deixar (ou tirar) o botao fixo na tela. Mapa e navegacao tem botoes separados.
 */
@Composable
fun QuickMenuDialog(vm: AppViewModel, nav: Boolean, onAction: (QuickAction) -> Unit, onDismiss: () -> Unit) {
    val pinned = vm.pinned(nav)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1E22),
        title = {
            Column {
                Text("☰ Menu", color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    "Toque no 📌 para deixar o botão na tela " + if (nav) "durante a navegação." else "do mapa.",
                    color = TextDim, fontSize = 13.sp,
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                // navegando, "Sair" fica no topo do menu
                QuickAction.entries.filter { it.available(nav) }
                    .sortedBy { if (nav && it == QuickAction.SAIR) 0 else 1 }
                    .forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider(color = PanelBorder)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.weight(1f).clickable { onAction(a); onDismiss() }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(a.icon(vm), fontSize = 24.sp)
                            Spacer(Modifier.width(14.dp))
                            Text(
                                a.label, fontSize = 17.sp,
                                color = if (a == QuickAction.SAIR) Danger else Color.White,
                                fontWeight = if (a == QuickAction.SAIR) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                        val on = a.id in pinned
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (on) Color(0xFF1A73E8) else Color(0xFF2A3036))
                                .clickable { vm.togglePin(nav, a.id) },
                            contentAlignment = Alignment.Center,
                        ) { Text("📌", fontSize = 20.sp, color = if (on) Color.White else TextDim) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar", fontSize = 16.sp) } },
    )
}

package com.motorota.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorota.AppViewModel
import com.motorota.BuildConfig

private data class MenuPage(val id: String, val title: String, val subtitle: String, val icon: ImageVector, val tint: Color)

/**
 * Menu completo (botão de três pontinhos): todas as configurações e ferramentas num lugar só,
 * organizadas por assunto. A tela do mapa fica limpa.
 */
@Composable
fun MenuScreen(
    vm: AppViewModel,
    withMic: (() -> Unit) -> Unit,
    onReport: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenTrips: () -> Unit,
    onClose: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler { if (page != null) page = null else onClose() }

    val pages = listOf(
        MenuPage("moto", "Minha moto", "Autonomia do tanque e abastecimento", Icons.Rounded.TwoWheeler, Color(0xFF60A5FA)),
        MenuPage("jarvis", "Jarvis", "Escuta, voz, chaves e teste de conexão", Icons.Rounded.Mic, Color(0xFF22D3EE)),
        MenuPage("apps", "Aplicativos e mensagens", "WhatsApp, ligações, música e permissões", Icons.Rounded.Apps, Color(0xFFA78BFA)),
        MenuPage("intercom", "Intercomunicador", "Seu nome, seu código e amigos", Icons.Rounded.Headset, NavGreen),
        MenuPage("mapa", "Mapa", "Tema, satélite e mapas offline", Icons.Rounded.Map, Color(0xFF34D399)),
        MenuPage("navegacao", "Navegação e segurança", "Avisos por voz e áreas de risco", Icons.Rounded.Shield, WarningColor),
        MenuPage("botoes", "Botões na tela", "Escolha os atalhos que ficam no mapa", Icons.Rounded.TouchApp, Color(0xFFF472B6)),
        MenuPage("sobre", "Sobre e atualização", "Versão do app", Icons.Rounded.Info, TextDim),
    )
    val current = pages.firstOrNull { it.id == page }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // ---- Cabeçalho ----
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (page != null) page = null else onClose() }) {
                    Icon(
                        if (page != null) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Close,
                        "Voltar",
                        tint = Color.White,
                    )
                }
                Text(
                    current?.title ?: "Menu",
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (page) {
                    null -> {
                        // Ações rápidas do momento
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            QuickTile(Icons.Rounded.WarningAmber, "Reportar alerta", WarningColor, Modifier.weight(1f)) {
                                onClose(); onReport()
                            }
                            if (vm.navigating) {
                                QuickTile(Icons.Rounded.ZoomOutMap, "Visão geral", Color(0xFF93C5FD), Modifier.weight(1f)) {
                                    onClose(); vm.overview()
                                }
                            } else {
                                QuickTile(Icons.Rounded.Star, "Meus passeios", Color(0xFFFDE047), Modifier.weight(1f)) {
                                    onOpenTrips()
                                }
                            }
                        }
                        if (vm.navigating) {
                            Button(
                                onClick = { onClose(); vm.stopNavigation() },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Danger),
                            ) { Text("Encerrar navegação", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                        }
                        Spacer(Modifier.height(2.dp))
                        pages.forEach { p -> MenuRow(p) { page = p.id } }
                        Spacer(Modifier.height(16.dp))
                    }

                    "moto" -> {
                        SettingsCards(vm, withMic, "moto")
                        InfoCard(
                            "Em breve: sua moto",
                            "Você vai poder escolher o modelo (ex.: Triumph Tiger 900) e o app já traz consumo médio, " +
                                "tamanho do tanque e dicas para o Jarvis planejar abastecimento e paradas.",
                        )
                    }
                    "jarvis" -> SettingsCards(vm, withMic, "jarvis")
                    "apps" -> SettingsCards(vm, withMic, "apps")
                    "intercom" -> SettingsCards(vm, withMic, "intercom")

                    "mapa" -> {
                        ToggleRow("Tema escuro (noite)", "Liga sozinho à noite", vm.night) { vm.night = !vm.night }
                        ToggleRow("Imagem de satélite", "Fundo com foto aérea", vm.satellite) { vm.toggleSatellite() }
                        ActionCard(Icons.Rounded.Download, "Mapas offline", "Baixe o mapa da região para calcular rotas sem internet") {
                            onOpenDownloads()
                        }
                    }

                    "navegacao" -> {
                        ToggleRow("Avisos por voz", "Manobras, radares e alertas falados", vm.voiceOn) { vm.toggleVoice() }
                        SettingsCards(vm, withMic, "navegacao")
                    }

                    "botoes" -> {
                        Text(
                            "Atalhos que ficam fixos na tela. Menos botões = mapa mais limpo.",
                            color = TextDim,
                            fontSize = 13.sp,
                        )
                        Text("No mapa", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        QuickAction.entries.filter { it.available(false) && it != QuickAction.AJUSTES }.forEach { a ->
                            ToggleRow(a.label, null, a.id in vm.pinned(false)) { vm.togglePin(false, a.id) }
                        }
                        Text("Navegando", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        QuickAction.entries.filter { it.available(true) && it != QuickAction.AJUSTES }.forEach { a ->
                            ToggleRow(a.label, null, a.id in vm.pinned(true)) { vm.togglePin(true, a.id) }
                        }
                    }

                    "sobre" -> {
                        InfoCard("MotoRota ${BuildConfig.VERSION_NAME}", "Navegação para moto do Grupo Conecta, com o Jarvis.")
                        Button(
                            onClick = { vm.installUpdate(null) },
                            enabled = vm.installProgress == null,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(vm.installProgress?.let { "Baixando… $it%" } ?: "Instalar a versão mais nova")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(p: MenuPage, onClick: () -> Unit) {
    Surface(
        color = SurfaceCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(p.tint.copy(alpha = 0.16f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(p.icon, null, tint = p.tint, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(p.subtitle, color = TextDim, fontSize = 12.5.sp)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = TextDim)
        }
    }
}

@Composable
private fun QuickTile(icon: ImageVector, label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        color = SurfaceCard,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.border(1.dp, PanelBorder, RoundedCornerShape(14.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(vertical = 14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onToggle: () -> Unit) {
    Surface(
        color = SurfaceCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)).clickable(onClick = onToggle),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 15.sp)
                if (subtitle != null) Text(subtitle, color = TextDim, fontSize = 12.5.sp)
            }
            Switch(checked = checked, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        color = SurfaceCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFF60A5FA), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TextDim, fontSize = 12.5.sp)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = TextDim)
        }
    }
}

@Composable
private fun InfoCard(title: String, text: String) {
    Surface(
        color = SurfaceCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(text, color = TextDim, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

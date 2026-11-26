package com.motorota.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

/** Grupos de pilotos e recados (Menu > Intercomunicador). */
@Composable
fun GroupsCard(vm: AppViewModel) {
    val ic = vm.intercom
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var info by remember { mutableStateOf("") }
    Surface(
        color = SurfaceCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Groups, null, tint = Color(0xFFA78BFA), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Grupos e recados", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Diga \"Jarvis, manda pro Renato: vou abastecer no próximo posto\" ou \"manda no grupo Família: ...\". " +
                    "O Jarvis de quem recebe fala o recado em voz alta. Sem internet, o recado espera e sai quando o sinal voltar.",
                color = TextDim,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
            )
            if (ic.pendingOut > 0) Text("⏳ ${ic.pendingOut} recado(s) esperando internet para sair", color = WarningColor, fontSize = 12.5.sp)
            ic.groups.forEach { g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(g.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(g.members.joinToString(", ") { it.name }, color = TextDim, fontSize = 12.5.sp)
                    }
                    IconButton(onClick = { ic.deleteGroup(g.id) }) {
                        Icon(Icons.Rounded.DeleteOutline, "Apagar grupo ${g.name}", tint = TextDim)
                    }
                }
            }
            if (!creating) {
                OutlinedButton(
                    onClick = { creating = true; info = "" },
                    enabled = ic.friends.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (ic.friends.isEmpty()) "Adicione amigos para criar um grupo" else "Novo grupo") }
            } else {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    label = { Text("Nome do grupo (ex.: Família, Trilha)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ic.friends.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            chosen = if (f.id in chosen) chosen - f.id else chosen + f.id
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = f.id in chosen, onCheckedChange = {
                            chosen = if (it) chosen + f.id else chosen - f.id
                        })
                        Text(f.name, color = Color.White, fontSize = 14.5.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { creating = false }, modifier = Modifier.weight(1f)) { Text("Cancelar") }
                    Button(
                        onClick = {
                            info = ic.createGroup(name, chosen)
                            if (info.contains("criado")) { creating = false; name = ""; chosen = emptySet() }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Criar") }
                }
            }
            if (info.isNotBlank()) Text(info, color = Color(0xFF93C5FD), fontSize = 12.5.sp)
            if (ic.recent.isNotEmpty()) {
                Text("Últimos recados", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                ic.recent.takeLast(5).reversed().forEach { r ->
                    Text(
                        "${r.fromName}${r.group?.let { " · $it" } ?: ""}: ${r.text}",
                        color = TextDim,
                        fontSize = 12.5.sp,
                    )
                }
            }
        }
    }
}

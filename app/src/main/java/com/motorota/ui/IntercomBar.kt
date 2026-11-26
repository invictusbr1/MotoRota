package com.motorota.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorota.AppViewModel
import com.motorota.intercom.CallState
import kotlinx.coroutines.delay

/** Faixa do intercomunicador: chamando / tocando (atender, recusar) / em chamada (mudo, desligar). */
@Composable
fun IntercomBar(vm: AppViewModel, withMic: (() -> Unit) -> Unit, modifier: Modifier = Modifier) {
    val ic = vm.intercom
    val st = ic.state
    AnimatedVisibility(visible = st != CallState.IDLE, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(st) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(1000)
            }
        }
        val title = when (st) {
            CallState.CALLING -> "Chamando ${ic.peerName}…"
            CallState.RINGING -> "${ic.peerName} quer falar com você"
            CallState.IN_CALL -> {
                val s = ((now - ic.callStartedAt) / 1000).coerceAtLeast(0)
                "${ic.peerName} · %d:%02d".format(s / 60, s % 60)
            }
            CallState.IDLE -> ""
        }
        val sub = when (st) {
            CallState.CALLING -> "Intercomunicador MotoRota"
            CallState.RINGING -> "Diga \"pode atender\" ou toque abaixo"
            CallState.IN_CALL -> when {
                ic.muted -> "Seu microfone está mudo"
                ic.peerTalking -> "Falando…"
                else -> if (ic.route.helmetConnected()) "Pelo capacete" else "Pelo alto-falante do celular"
            }
            CallState.IDLE -> ""
        }
        Surface(
            color = Color(0xF2071A12),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .shadow(10.dp, RoundedCornerShape(18.dp))
                .border(1.dp, NavGreen.copy(alpha = 0.6f), RoundedCornerShape(18.dp)),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(NavGreen.copy(alpha = if (ic.peerTalking) 0.45f else 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Headset, null, tint = NavGreen, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(sub, color = TextDim, fontSize = 13.sp, maxLines = 1)
                    }
                    when (st) {
                        CallState.IN_CALL -> {
                            IconButton(onClick = { ic.muted = !ic.muted }) {
                                Icon(
                                    if (ic.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                    if (ic.muted) "Ligar microfone" else "Mudo",
                                    tint = if (ic.muted) WarningColor else Color.White,
                                )
                            }
                            HangButton { ic.hangUp() }
                        }
                        CallState.CALLING -> HangButton { ic.hangUp() }
                        else -> {}
                    }
                }
                if (st == CallState.RINGING) {
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Button(
                            onClick = { ic.reject() },
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Danger),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Rounded.CallEnd, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Recusar", fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = { withMic { ic.accept() } },
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NavGreen, contentColor = Color(0xFF042617)),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Rounded.Call, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Atender", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HangButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Danger),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) {
            Icon(Icons.Rounded.CallEnd, "Desligar", tint = Color.White)
        }
    }
}

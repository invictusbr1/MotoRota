package com.motorota.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorota.AppViewModel
import com.motorota.assistant.JarvisStatus
import kotlinx.coroutines.delay

private val JarvisCyan = Color(0xFF22D3EE)

/** Balão do Jarvis: mostra "ouvindo", o que foi entendido e a resposta. Toque para fechar/cancelar. */
@Composable
fun JarvisBubble(vm: AppViewModel, modifier: Modifier = Modifier) {
    val j = vm.jarvis
    val st = j.status
    var showReply by remember { mutableStateOf(false) }

    // mantém a resposta na tela alguns segundos depois que ele termina de falar
    LaunchedEffect(st, j.replyVersion) {
        if (st == JarvisStatus.SPEAKING) showReply = true
        else if (!j.busyNow && showReply) {
            delay(6000)
            showReply = false
        }
    }

    val active = j.busyNow || st == JarvisStatus.PREPARING
    AnimatedVisibility(
        visible = active || (showReply && j.reply.isNotBlank()),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val pulse = rememberInfiniteTransition(label = "jarvis")
        val a by pulse.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "pulso",
        )
        val title = when (st) {
            JarvisStatus.PREPARING -> "Preparando o Jarvis… ${j.prepareProgress}%"
            JarvisStatus.LISTENING -> "Ouvindo…"
            JarvisStatus.THINKING -> "Pensando…"
            JarvisStatus.SPEAKING -> "Jarvis"
            else -> "Jarvis"
        }
        val body = when (st) {
            JarvisStatus.LISTENING, JarvisStatus.THINKING -> j.heard
            JarvisStatus.PREPARING -> "Baixando o reconhecedor de voz (só na primeira vez)."
            else -> j.reply
        }
        Surface(
            color = Color(0xF20B1220),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .shadow(10.dp, RoundedCornerShape(18.dp))
                .border(1.dp, JarvisCyan.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                .clickable {
                    if (j.busyNow) j.cancel() else { showReply = false; j.dismissReply() }
                },
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .alpha(if (st == JarvisStatus.LISTENING || st == JarvisStatus.THINKING) a else 1f)
                        .background(JarvisCyan.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Mic, null, tint = JarvisCyan, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = JarvisCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    if (body.isNotBlank()) {
                        Text(
                            body,
                            color = Color.White,
                            fontSize = 16.sp,
                            lineHeight = 21.sp,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

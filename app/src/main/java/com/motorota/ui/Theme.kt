package com.motorota.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.RoundaboutRight
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSharpLeft
import androidx.compose.material.icons.rounded.TurnSharpRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorota.routing.Mode
import com.motorota.routing.RouteHint

// Paleta automotiva escura de alto contraste (pensada para sol forte e pilotagem noturna)
val Bg = Color(0xFF0E1116)
val Panel = Color(0xF5151A21)
val SurfaceCard = Color(0xFF1D242E)
val SurfaceElevated = Color(0xFF262F3C)
val PanelBorder = Color(0xFF2D3744)
val TextPrimary = Color(0xFFF8FAFC)
val TextDim = Color(0xFF94A3B8)

val Amber = Color(0xFF3B82F6)
val Blue = Color(0xFF2563EB)
val NavGreen = Color(0xFF10B981)
val WarningColor = Color(0xFFF59E0B)
val Danger = Color(0xFFEF4444)

fun Mode.color(): Color = when (this) {
    Mode.SEGURA -> Color(0xFF10B981)
    Mode.DESEMPENHO -> Color(0xFF3B82F6)
    Mode.RAPIDA -> Color(0xFFF59E0B)
}

@Composable
fun MotoRotaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF60A5FA),
            onPrimary = Color(0xFF081426),
            secondary = NavGreen,
            surface = Color(0xFF151A21),
            surfaceVariant = SurfaceCard,
            background = Bg,
            error = Danger,
        ),
        content = content,
    )
}

@Composable
fun RoundButton(
    onClick: () -> Unit,
    highlight: Boolean = false,
    size: Dp = 52.dp,
    content: @Composable () -> Unit,
) {
    val bg = if (highlight) Blue else Panel
    val borderCol = if (highlight) Color(0xFF60A5FA) else PanelBorder
    Box(
        Modifier
            .size(size)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, borderCol, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun StatusPill(
    icon: ImageVector? = null,
    emoji: String? = null,
    text: String,
    tint: Color = TextDim,
    bg: Color = SurfaceCard,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, PanelBorder.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
        } else if (emoji != null) {
            Text(emoji, fontSize = 13.sp)
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Ícone vetorial nítido para cada comando de manobra do BRouter. */
@Composable
fun ManeuverIcon(hint: RouteHint?, size: Dp = 52.dp, tint: Color = Color.White) {
    val vec: ImageVector = when (hint?.cmd) {
        null -> Icons.Rounded.Flag
        1, 16 -> Icons.Rounded.Straight
        2 -> Icons.Rounded.TurnLeft
        3, 8 -> Icons.Rounded.TurnSlightLeft
        4 -> Icons.Rounded.TurnSharpLeft
        5 -> Icons.Rounded.TurnRight
        6, 9 -> Icons.Rounded.TurnSlightRight
        7 -> Icons.Rounded.TurnSharpRight
        10, 11, 15 -> Icons.Rounded.UTurnLeft
        13, 14 -> Icons.Rounded.RoundaboutRight
        17 -> Icons.AutoMirrored.Rounded.CallSplit
        18 -> Icons.AutoMirrored.Rounded.CallSplit
        else -> Icons.Rounded.Straight
    }
    Icon(vec, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

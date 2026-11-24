package com.motorota.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorota.R

val ConectaNavy = Color(0xFF010A30)
val ConectaCyan = Color(0xFF22D3EE)

@Composable
fun SplashScreen() {
    Column(
        Modifier.fillMaxSize().background(ConectaNavy).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.logo_grupo_conecta),
            contentDescription = "Grupo Conecta",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().widthIn(max = 280.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text("MotoRota", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(4.dp))
        Text("Navegação inteligente para motociclistas", color = ConectaCyan, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

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
        Modifier.fillMaxSize().background(ConectaNavy).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.logo_grupo_conecta),
            contentDescription = "Grupo Conecta",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(28.dp))
        Text("MotoRota", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("navegação para moto", color = ConectaCyan, fontSize = 15.sp)
    }
}

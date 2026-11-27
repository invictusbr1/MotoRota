package com.motorota.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorota.AppViewModel
import com.motorota.data.BikeModel
import com.motorota.data.BikePhoto
import com.motorota.data.BikeProfile
import com.motorota.data.Bikes
import java.util.Locale

private fun fmt(d: Double, dec: Int = 1) = String.format(Locale("pt", "BR"), "%.${dec}f", d)

/** Cartão grande da moto: foto do piloto (ou desenho na cor do modelo) + nome. */
@Composable
fun BikeHero(b: BikeProfile, onPhoto: (() -> Unit)?) {
    val base = Color(b.model?.color ?: 0xFF334155)
    val bmp = remember(b.photo) { BikePhoto.load(b.photo)?.asImageBitmap() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(base, base.copy(alpha = 0.55f), Color(0xFF0E1116)))),
    ) {
        if (bmp != null) {
            Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
        } else {
            Icon(
                Icons.Rounded.TwoWheeler, null,
                tint = Color.White.copy(alpha = 0.22f),
                modifier = Modifier.size(150.dp).align(Alignment.CenterEnd).padding(end = 12.dp),
            )
        }
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            b.model?.let { Text(it.brand.uppercase(), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            Text(b.model?.name ?: b.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
            b.model?.let { Text("${it.style} · ${it.engine}", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp) }
        }
        if (onPhoto != null) {
            Surface(
                color = Color(0x99000000),
                shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clickable(onClick = onPhoto),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AddAPhoto, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (b.photo == null) "Pôr foto" else "Trocar foto", color = Color.White, fontSize = 12.5.sp)
                }
            }
        }
    }
}

@Composable
private fun ModelPicker(selected: String?, onPick: (BikeModel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Bikes.CATALOG.forEach { m ->
            val sel = m.id == selected
            Surface(
                color = if (sel) Color(m.color).copy(alpha = 0.35f) else SurfaceCard,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(if (sel) 2.dp else 1.dp, if (sel) Color(0xFF60A5FA) else PanelBorder, RoundedCornerShape(14.dp))
                    .clickable { onPick(m) },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Color(m.color)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.TwoWheeler, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.fullName, color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                        Text("${m.style} · tanque ${fmt(m.tankL)} L", color = TextDim, fontSize = 12.5.sp)
                    }
                    if (sel) Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF60A5FA))
                }
            }
        }
    }
}

/** Menu > Minha moto */
@Composable
fun GarageSection(vm: AppViewModel) {
    val b = vm.bike
    var picking by remember { mutableStateOf(false) }
    var liters by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf(vm.riderName) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setBikePhoto(uri)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = nameText,
                onValueChange = { nameText = it.take(40) },
                label = { Text("Seu nome") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.saveRiderName(nameText) }) { Text("Salvar") }
        }

        if (b == null || picking) {
            Text(if (b == null) "Escolha sua moto" else "Trocar de moto", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            ModelPicker(b?.modelId) { m -> vm.chooseBike(m.id); picking = false }
            Text(
                "Não achou a sua? Me conte o modelo que eu adiciono com os dados oficiais.",
                color = TextDim,
                fontSize = 12.5.sp,
            )
            if (b != null) TextButton(onClick = { picking = false }) { Text("Cancelar") }
            return@Column
        }

        BikeHero(b) { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

        // ---- Combustível agora ----
        val pct = (b.leftL / b.tankL).toFloat().coerceIn(0f, 1f)
        val barColor = when {
            b.leftL <= b.reserveL -> Danger
            pct < 0.35f -> WarningColor
            else -> NavGreen
        }
        Surface(
            color = SurfaceCard,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LocalGasStation, null, tint = barColor, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Autonomia agora", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("~${b.leftKm} km", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                }
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF2D3744))) {
                    Box(Modifier.fillMaxWidth(pct).height(10.dp).clip(RoundedCornerShape(5.dp)).background(barColor))
                }
                Text(
                    "≈ ${fmt(b.leftL)} de ${fmt(b.tankL)} L · margem de erro ±${b.marginPct}%" +
                        if (b.leftL <= b.reserveL) " · NA RESERVA" else "",
                    color = TextDim,
                    fontSize = 12.5.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = liters,
                        onValueChange = { t -> liters = t.filter { it.isDigit() || it == ',' || it == '.' }.take(5) },
                        label = { Text("Litros que entraram (opcional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            vm.refuelBike(liters.replace(',', '.').toDoubleOrNull())
                            liters = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NavGreen, contentColor = Color(0xFF042617)),
                    ) { Text("Abasteci", fontWeight = FontWeight.Bold) }
                }
                Text(
                    "Encheu o tanque? Toque em Abasteci. Se disser quantos litros entraram, o app compara com o que estimou e " +
                        "aprende o consumo real da SUA moto.",
                    color = TextDim,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        // ---- Consumo ----
        Surface(
            color = SurfaceCard,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Consumo", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                b.model?.let { m ->
                    InfoLine("Oficial do fabricante", "${fmt(m.officialKmL)} km/L")
                }
                InfoLine("Estimado para você", "${fmt(b.kmL)} km/L (±${b.marginPct}%)")
                InfoLine("Tanque cheio rende", "~${b.fullRangeKm} km")
                InfoLine("Reserva", "~${fmt(b.reserveL)} L (${(b.reserveL * b.kmL).toInt()} km)")
                if (b.learnedFills > 0) InfoLine("Aprendido em", "${b.learnedFills} abastecimento(s)")
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ajuste automático", color = Color.White, fontSize = 14.5.sp)
                        Text(
                            "Usa sua velocidade (estrada rápida gasta mais, anda-e-para também) e os abastecimentos para afinar a conta.",
                            color = TextDim,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = b.autoAdjust, onCheckedChange = { vm.toggleBikeAuto() })
                }
            }
        }

        FuelPriceCard(vm)

        b.model?.let { m ->
            Surface(
                color = SurfaceCard,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Ficha da moto", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    m.highlights.forEach { Text("• $it", color = TextDim, fontSize = 13.sp) }
                }
            }
        }

        OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text("Trocar de moto") }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(label, color = TextDim, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** Primeira abertura: nome do piloto e a moto (sem cadastro complicado). */
@Composable
fun WelcomeScreen(vm: AppViewModel) {
    var name by rememberSaveable { mutableStateOf(vm.riderName) }
    var model by rememberSaveable { mutableStateOf<String?>(vm.bike?.modelId) }
    BackHandler { }
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            Text("Bem-vindo ao MotoRota", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text("Só duas coisas e a gente sai rodando.", color = TextDim, fontSize = 15.sp)

            Text("Como você se chama?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                placeholder = { Text("Seu nome") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Seus amigos te encontram por esse nome no intercomunicador.", color = TextDim, fontSize = 12.5.sp)

            Text("Qual é a sua moto?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            ModelPicker(model) { m -> model = if (model == m.id) null else m.id }
            Text("Com a moto escolhida, o app calcula consumo e autonomia sozinho. Dá para pôr a foto dela depois.", color = TextDim, fontSize = 12.5.sp)

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { vm.finishWelcome(name, model) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Começar", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            TextButton(onClick = { vm.finishWelcome(name, null) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (model == null) "Escolho a moto depois" else "Pular", color = TextDim)
            }
        }
    }
}


/** Gasolina usada no cálculo do custo da viagem: comum/aditivada pela média da ANP, ou preço próprio. */
@Composable
private fun FuelPriceCard(vm: AppViewModel) {
    var priceText by remember { mutableStateOf(if (vm.fuelPriceManual > 0) fmt(vm.fuelPriceManual, 2) else "") }
    Surface(
        color = SurfaceCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(14.dp)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Custo das viagens", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Ao traçar a rota, o app mostra quanto vai gastar de gasolina com o consumo da sua moto e o preço médio " +
                    "de cada estado do caminho (ANP, atualizado toda semana" +
                    (com.motorota.data.FuelPrices.week?.let { ": $it" } ?: "") + ").",
                color = TextDim, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("comum" to "Gasolina comum", "aditivada" to "Aditivada").forEach { (k, l) ->
                    val sel = vm.fuelType == k
                    Surface(
                        color = if (sel) Color(0xFF1D4ED8) else Color(0xFF2D3744),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f).clickable { vm.setFuel(k, vm.fuelPriceManual) },
                    ) {
                        Text(
                            l, color = Color.White, fontSize = 13.5.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { t -> priceText = t.filter { it.isDigit() || it == ',' || it == '.' }.take(5) },
                    label = { Text("Preço próprio R$/L (vazio = média ANP)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.setFuel(vm.fuelType, priceText.replace(',', '.').toDoubleOrNull() ?: 0.0) }) { Text("Salvar") }
            }
        }
    }
}

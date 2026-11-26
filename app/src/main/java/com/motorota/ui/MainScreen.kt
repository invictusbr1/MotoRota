package com.motorota.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.motorota.AppViewModel
import com.motorota.routing.GeoPoint
import com.motorota.routing.Waypoint
import kotlinx.coroutines.delay

@Composable
fun MainScreen(vm: AppViewModel, hasLocationPermission: Boolean) {
    var showDownloads by remember { mutableStateOf(false) }
    var pressPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var showTrips by remember { mutableStateOf(false) }
    var showSaveTrip by remember { mutableStateOf(false) }
    var reportAt by remember { mutableStateOf<GeoPoint?>(null) }
    var pickedStop by remember { mutableStateOf<Int?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // Monitora downloads de mapas em segundo plano
    LaunchedEffect(Unit) {
        while (true) {
            vm.pollDownloads()
            delay(if (vm.downloadProgress.pending > 0) 1500 else 5000)
        }
    }

    // Microfone: pede a permissão na hora em que o Jarvis é usado pela primeira vez
    var afterMic by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) afterMic?.invoke() else vm.message = "Sem o microfone o Jarvis não consegue ouvir."
        afterMic = null
    }
    val withMic: (() -> Unit) -> Unit = { run ->
        if (vm.jarvis.hasMic()) run() else {
            afterMic = run
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Liga/desliga o microfone do Jarvis quando o app aparece ou some da tela
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> vm.jarvis.setForeground(true)
                Lifecycle.Event.ON_STOP -> vm.jarvis.setForeground(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val onAction: (QuickAction) -> Unit = { action ->
        when (action) {
            QuickAction.PASSEIOS -> showTrips = true
            QuickAction.TEMA -> vm.night = !vm.night
            QuickAction.SATELITE -> vm.toggleSatellite()
            QuickAction.AVISAR -> vm.myLocation?.let { reportAt = it }
                ?: run { vm.message = "Sem sinal de GPS para reportar na posição atual." }
            QuickAction.VOZ -> vm.toggleVoice()
            QuickAction.ABASTECI -> if (vm.tankKm > 0) vm.refuel() else {
                vm.message = "Configure primeiro a autonomia do tanque."
                showSettings = true
            }
            QuickAction.GERAL -> vm.overview()
            QuickAction.MAPAS -> showDownloads = true
            QuickAction.AJUSTES -> showSettings = true
            QuickAction.SAIR -> vm.stopNavigation()
            QuickAction.JARVIS -> withMic { vm.jarvis.trigger() }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        MapLibreMapView(
            vm = vm,
            hasLocationPermission = hasLocationPermission,
            bottomPaddingDp = when {
                vm.destination != null && vm.editMode -> 240
                vm.destination != null -> 320
                else -> 48
            },
            onLongPress = { if (!vm.navigating && !vm.editMode) pressPoint = it },
            onTap = { p, hit ->
                if (vm.editMode) {
                    val moving = vm.movingStop
                    when {
                        moving != null -> vm.moveStopTo(moving, p)
                        hit != null -> pickedStop = hit
                        else -> vm.addViaSmart(p)
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (vm.navigating) {
            NavigationHud(vm, onAction = onAction, onMenu = { showMenu = true })
        } else {
            PlanningOverlay(
                vm = vm,
                onOpenDownloads = { showDownloads = true },
                onSaveTrip = { showSaveTrip = true },
                onAction = onAction,
                onMenu = { showMenu = true },
            )
        }

        JarvisBubble(
            vm,
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = if (vm.navigating) 196.dp else 84.dp, start = 16.dp, end = 16.dp)
                .zIndex(11f),
        )

        // Toast flutuante discreto no topo/centro (não bloqueia o botão INICIAR)
        val msg = vm.message
        AnimatedVisibility(
            visible = msg != null,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 84.dp, start = 20.dp, end = 20.dp)
                .zIndex(10f),
        ) {
            if (msg != null) {
                LaunchedEffect(msg) {
                    delay(4000)
                    if (vm.message == msg) vm.message = null
                }
                Surface(
                    color = Color(0xF51E293B),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .shadow(8.dp, RoundedCornerShape(14.dp))
                        .border(1.dp, PanelBorder, RoundedCornerShape(14.dp))
                        .clickable { vm.message = null },
                ) {
                    Text(
                        msg,
                        color = Color.White,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    pressPoint?.let { p ->
        LongPressDialog(vm, p, onReport = { reportAt = p }, onDismiss = { pressPoint = null })
    }
    reportAt?.let { p ->
        HazardDialog(vm, p, onDismiss = { reportAt = null })
    }
    pickedStop?.let { i ->
        StopActionDialog(vm, i, onDismiss = { pickedStop = null })
    }
    if (showTrips) TripsDialog(vm, onDismiss = { showTrips = false })
    if (showMenu) {
        QuickMenuDialog(
            vm = vm,
            nav = vm.navigating,
            onAction = onAction,
            onDismiss = { showMenu = false },
        )
    }
    if (showSettings) {
        SettingsDialog(
            vm = vm,
            withMic = withMic,
            onDismiss = {
                showSettings = false
                focusManager.clearFocus(force = true)
                keyboard?.hide()
            },
        )
    }
    vm.tripSummary?.let { TripSummaryDialog(it, onDismiss = { vm.tripSummary = null }) }
    if (showSaveTrip) SaveTripDialog(vm, onDismiss = { showSaveTrip = false })
    if (showDownloads) DownloadDialog(vm, onDismiss = { showDownloads = false })
}

/**
 * Camada de planejamento estruturada em `Box` para que a busca, os botões flutuantes
 * e o painel de rota nunca colidam nem empurrem elementos para fora da tela.
 */
@Composable
private fun PlanningOverlay(
    vm: AppViewModel,
    onOpenDownloads: () -> Unit,
    onSaveTrip: () -> Unit,
    onAction: (QuickAction) -> Unit,
    onMenu: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val hasRouteSheet = vm.destination != null

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {

        // ---- 1. Topo: Barra de busca limpa + Resultados sobrepostos + Avisos ----
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .zIndex(5f)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Panel,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .shadow(8.dp, RoundedCornerShape(16.dp))
                        .border(1.dp, PanelBorder, RoundedCornerShape(16.dp)),
                ) {
                    TextField(
                        value = query,
                        onValueChange = { query = it; vm.search(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                if (vm.destination == null) "Para onde vamos?" else "Adicionar parada na rota…",
                                fontSize = 16.sp,
                                color = TextDim,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (vm.destination == null) Icons.Rounded.Search else Icons.Rounded.AddLocationAlt,
                                contentDescription = null,
                                tint = Color(0xFF60A5FA),
                            )
                        },
                        trailingIcon = {
                            if (vm.searching) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else if (query.isNotEmpty()) {
                                IconButton(onClick = { query = ""; vm.clearSearch() }) {
                                    Icon(Icons.Rounded.Close, "Limpar busca", tint = TextDim)
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 16.sp, color = Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.search(query); focus.clearFocus() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                        ),
                    )
                }

                Spacer(Modifier.width(8.dp))
                RoundButton(
                    onClick = onOpenDownloads,
                    highlight = !vm.hasMaps || vm.downloadProgress.pending > 0,
                    size = 52.dp,
                ) {
                    Icon(Icons.Rounded.Download, "Mapas offline", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }

            // Lista suspensa de resultados da busca (flutua por cima sem empurrar a tela)
            if (vm.searchResults.isNotEmpty()) {
                Surface(
                    color = Panel,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .shadow(12.dp, RoundedCornerShape(16.dp))
                        .border(1.dp, PanelBorder, RoundedCornerShape(16.dp)),
                ) {
                    LazyColumn {
                        items(vm.searchResults) { place ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        focus.clearFocus()
                                        if (vm.destination == null) {
                                            query = ""
                                            vm.goTo(Waypoint(place.point, place.name))
                                        } else {
                                            query = ""
                                            vm.addViaSmart(place.point, place.name)
                                            vm.clearSearch()
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Place, null, tint = Color(0xFF60A5FA), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(place.name, color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                                    if (place.detail.isNotBlank()) {
                                        Text(place.detail, color = TextDim, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            HorizontalDivider(color = PanelBorder.copy(alpha = 0.6f))
                        }
                    }
                }
            }

            // Aviso de atualização disponível no GitHub
            vm.update?.let { up ->
                Surface(
                    color = Color(0xF51E3A8A),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().shadow(6.dp, RoundedCornerShape(14.dp)),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Nova versão disponível (${up.version})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                            Text("Mapas e passeios salvos são mantidos.", color = Color(0xFFBFDBFE), fontSize = 12.sp)
                        }
                        Button(
                            onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(up.apkUrl))) },
                            shape = RoundedCornerShape(10.dp),
                        ) { Text("Atualizar") }
                        IconButton(onClick = { vm.update = null }) {
                            Icon(Icons.Rounded.Close, "Agora não", tint = Color.White)
                        }
                    }
                }
            }

            // Cartão de primeiro acesso caso nenhum mapa tenha sido baixado
            if (!vm.hasMaps && vm.downloadProgress.pending == 0 && vm.searchResults.isEmpty()) {
                Surface(
                    color = Panel,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF3B82F6), RoundedCornerShape(14.dp))
                        .clickable { onOpenDownloads() },
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Download, null, tint = Color(0xFF60A5FA), modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Primeiro passo: baixar mapas offline", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                "Toque para baixar Brasil, Argentina, Chile ou Uruguai e calcular rotas sem internet.",
                                color = TextDim,
                                fontSize = 12.5.sp,
                            )
                        }
                    }
                }
            }
        }

        // ---- 2. Lateral Direita: Botões flutuantes (compactam quando o painel de rota está aberto) ----
        Column(
            Modifier
                .align(if (hasRouteSheet) Alignment.TopEnd else Alignment.BottomEnd)
                .padding(
                    top = if (hasRouteSheet) 78.dp else 0.dp,
                    bottom = if (hasRouteSheet) 0.dp else if (vm.showMapTip) 68.dp else 20.dp,
                    end = 12.dp,
                ),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoundButton(onClick = onMenu, size = if (hasRouteSheet) 46.dp else 50.dp) {
                Icon(Icons.Rounded.Menu, "Menu", tint = Color.White, modifier = Modifier.size(23.dp))
            }
            if (!hasRouteSheet) {
                PinnedButtons(vm, nav = false, maxVisible = 4, buttonSize = 50.dp, onAction = onAction)
            }
            RoundButton(onClick = { vm.centerOnMe() }, size = if (hasRouteSheet) 46.dp else 50.dp) {
                Icon(Icons.Rounded.MyLocation, "Minha posição", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }

        // ---- 3. Rodapé: Painel de Rota, Edição ou Dica inicial dispensável ----
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            when {
                vm.destination != null && vm.editMode -> EditBar(vm, onSaveTrip)
                vm.destination != null -> RouteSheet(vm, onSaveTrip)
                vm.showMapTip -> {
                    Surface(
                        color = Panel,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .border(1.dp, PanelBorder, RoundedCornerShape(14.dp)),
                    ) {
                        Row(
                            Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.TouchApp, null, tint = Color(0xFF60A5FA), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Toque e segure no mapa para marcar destino, parada ou área de risco.",
                                color = TextDim,
                                fontSize = 12.5.sp,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { vm.dismissMapTip() }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.Close, "Fechar dica", tint = TextDim, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

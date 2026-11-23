package com.motorota.ui

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.motorota.AppViewModel
import com.motorota.routing.GeoPoint
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

@SuppressLint("MissingPermission")
@Composable
fun MapLibreMapView(
    vm: AppViewModel,
    hasLocationPermission: Boolean,
    bottomPaddingDp: Int,
    onLongPress: (GeoPoint) -> Unit,
    onTap: (GeoPoint, Int?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var centeredOnce by remember { mutableStateOf(false) }
    var currentZoom by remember { mutableStateOf(16.5) }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(Unit) {
        mapView.getMapAsync { m ->
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.isRotateGesturesEnabled = true
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isAttributionEnabled = true
            m.cameraPosition = CameraPosition.Builder().target(LatLng(-22.9068, -43.1729)).zoom(10.0).build()
            m.addOnMapLongClickListener { p ->
                onLongPress(GeoPoint(p.latitude, p.longitude)); true
            }
            m.addOnMapClickListener { p ->
                // toque perto de uma parada (ate ~32dp na tela) = escolher a parada
                val tap = m.projection.toScreenLocation(p)
                val limit = with(density) { 32.dp.toPx() }
                var hit: Int? = null
                var best = Float.MAX_VALUE
                vm.stops.forEachIndexed { i, w ->
                    val sp = m.projection.toScreenLocation(LatLng(w.point.lat, w.point.lon))
                    val d = kotlin.math.hypot(sp.x - tap.x, sp.y - tap.y)
                    if (d < limit && d < best) { best = d; hit = i }
                }
                onTap(GeoPoint(p.latitude, p.longitude), hit)
                vm.editMode
            }
            m.setStyle(Style.Builder().fromUri(MapLayers.STYLE_URL)) { s ->
                MapLayers.setup(s)
                MapLayers.applyTheme(s, vm.night)
                map = m
                style = s
            }
        }
    }

    // ponto azul do GPS
    LaunchedEffect(style, hasLocationPermission) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        if (!hasLocationPermission) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return@LaunchedEffect
        val lc = m.locationComponent
        if (!lc.isLocationComponentActivated) {
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, s)
                    .useDefaultLocationEngine(false)
                    .build()
            )
        }
        lc.isLocationComponentEnabled = true
        lc.cameraMode = CameraMode.NONE
        lc.renderMode = RenderMode.GPS
    }

    LaunchedEffect(style, vm.routes, vm.mode, vm.navigating) {
        style?.let { MapLayers.updateRoutes(it, vm.routes, vm.mode, vm.navigating) }
    }
    LaunchedEffect(style, vm.radarsVersion) {
        style?.let { MapLayers.updateRadars(it, vm.radarStore.all) }
    }
    LaunchedEffect(style, vm.night, vm.satellite) {
        style?.let {
            MapLayers.applyTheme(it, vm.night)
            MapLayers.setSatellite(it, vm.satellite)
        }
    }
    LaunchedEffect(style, vm.hazards) {
        style?.let { MapLayers.updateHazards(it, vm.hazards) }
    }
    LaunchedEffect(style, vm.risks) {
        style?.let { MapLayers.updateRisks(it, vm.risks) }
    }
    LaunchedEffect(style, vm.knownRisksVersion) {
        style?.let { MapLayers.updateKnownRisks(it, vm.known()) }
    }
    LaunchedEffect(style, vm.origin, vm.stops, vm.destination) {
        style?.let { MapLayers.updateWaypoints(it, vm.origin, vm.stops, vm.destination) }
    }

    // posicao do GPS: atualiza o ponto azul (a animacao e feita pelo proprio mapa)
    LaunchedEffect(style, vm.lastLocation) {
        val m = map ?: return@LaunchedEffect
        val loc = vm.lastLocation ?: return@LaunchedEffect
        if (m.locationComponent.isLocationComponentActivated) {
            m.locationComponent.forceLocationUpdate(Location(loc))
        }
        if (vm.navigating) {
            // zoom muda devagar e so quando a velocidade muda de verdade;
            // perto de uma manobra (bifurcacao, saida, curva) aproxima mais
            val target = navZoom(vm.speedKmh, vm.navState?.distToHintM)
            // compara com o zoom REAL da camera (outra animacao pode ter mudado)
            if (kotlin.math.abs(target - m.cameraPosition.zoom) > 0.3 && kotlin.math.abs(target - currentZoom) > 0.05 ||
                kotlin.math.abs(target - m.cameraPosition.zoom) > 1.0) {
                currentZoom = target
                if (m.locationComponent.isLocationComponentActivated) {
                    m.locationComponent.zoomWhileTracking(target, 2500)
                }
            }
            if (!m.locationComponent.isLocationComponentActivated) {
                m.easeCamera(CameraUpdateFactory.newLatLng(LatLng(loc.latitude, loc.longitude)), 1000, false)
            }
        } else if (!centeredOnce) {
            centeredOnce = true
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 14.0))
        }
    }

    // entrar / sair do modo navegacao: a camera "gruda" na moto, inclinada, girando com a direcao
    LaunchedEffect(style, vm.navigating) {
        val m = map ?: return@LaunchedEffect
        val lc = m.locationComponent
        if (!lc.isLocationComponentActivated) return@LaunchedEffect
        if (vm.navigating) {
            currentZoom = navZoom(vm.speedKmh, vm.navState?.distToHintM)
            // moto no terco de baixo da tela: mais estrada visivel a frente
            // (o enquadramento vem ANTES de ligar o "seguir", senao ele desliga o modo)
            // moto bem embaixo (~78% da altura) e camera baixa/inclinada: ve a estrada la na frente
            val h = m.height.toDouble().takeIf { it > 0 } ?: with(density) { 800.dp.toPx().toDouble() }
            m.moveCamera(CameraUpdateFactory.paddingTo(0.0, h * 0.56, 0.0, 0.0))
            lc.cameraMode = CameraMode.TRACKING_GPS
            lc.tiltWhileTracking(60.0)
            lc.zoomWhileTracking(currentZoom)
        } else {
            lc.cameraMode = CameraMode.NONE
            m.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
            m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().tilt(0.0).bearing(0.0).build()))
        }
    }

    // navegando: a linha azul so mostra o que falta (o trecho percorrido some)
    LaunchedEffect(style, vm.navState) {
        val s = style ?: return@LaunchedEffect
        val st = vm.navState ?: return@LaunchedEffect
        val r = vm.currentRoute ?: return@LaunchedEffect
        if (!vm.navigating) return@LaunchedEffect
        MapLayers.updateRemaining(s, r, st.progressIndex, st.snapped ?: return@LaunchedEffect)
    }

    // enquadrar a rota
    LaunchedEffect(vm.fitRequest, style) {
        val m = map ?: return@LaunchedEffect
        if (vm.navigating) return@LaunchedEffect
        val r = vm.currentRoute ?: return@LaunchedEffect
        if (r.points.size < 2) return@LaunchedEffect
        val b = LatLngBounds.Builder()
        r.points.forEach { b.include(LatLng(it.lat, it.lon)) }
        val pad = with(density) { 48.dp.toPx().toInt() }
        val bottom = with(density) { bottomPaddingDp.dp.toPx().toInt() }
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 0.0, 0.0, pad, pad * 3, pad, bottom + pad), 800)
    }

    // botao "centralizar"
    LaunchedEffect(vm.centerRequest) {
        if (vm.centerRequest == 0) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val p = vm.myLocation ?: return@LaunchedEffect
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 15.0))
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/**
 * Zoom da navegacao: perto da moto (17.6 na cidade, 16.4 na estrada a 110 km/h)
 * e +0.8 quando falta pouco para uma manobra, para nao confundir em bifurcacoes.
 */
private fun navZoom(speedKmh: Int, distToManeuverM: Double?): Double {
    val base = 17.6 - ((speedKmh - 30).coerceIn(0, 80) / 80.0) * 1.2
    val nearTurn = distToManeuverM != null && distToManeuverM < 350
    return (base + if (nearTurn) 0.8 else 0.0).coerceAtMost(18.4)
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())

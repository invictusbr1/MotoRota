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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.motorota.AppViewModel
import com.motorota.routing.GeoPoint
import com.motorota.routing.Mode
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
import kotlin.math.abs
import kotlin.math.hypot

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
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnTap by rememberUpdatedState(onTap)

    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var centeredOnce by remember { mutableStateOf(false) }
    var wasNavigating by remember { mutableStateOf(false) }
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
            m.uiSettings.isLogoEnabled = false
            m.cameraPosition = CameraPosition.Builder().target(LatLng(-22.9068, -43.1729)).zoom(10.0).build()

            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && vm.navigating) {
                    vm.following = false
                }
            }
            // estabelecimentos atualizados (TomTom) da área que está na tela, só com zoom de perto
            m.addOnCameraIdleListener {
                if (m.cameraPosition.zoom < 14.0) return@addOnCameraIdleListener
                val b = m.projection.visibleRegion.latLngBounds
                val c = b.center
                val r = c.distanceTo(LatLng(b.latitudeNorth, b.longitudeEast)).toInt()
                vm.loadPoisAround(GeoPoint(c.latitude, c.longitude), r)
            }
            m.addOnMapLongClickListener { p ->
                currentOnLongPress(GeoPoint(p.latitude, p.longitude))
                true
            }
            m.addOnMapClickListener { p ->
                val tap = m.projection.toScreenLocation(p)
                val geo = GeoPoint(p.latitude, p.longitude)
                if (vm.editMode) {
                    val limit = with(density) { 32.dp.toPx() }
                    var hit: Int? = null
                    var best = Float.MAX_VALUE
                    vm.stops.forEachIndexed { i, w ->
                        val sp = m.projection.toScreenLocation(LatLng(w.point.lat, w.point.lon))
                        val d = hypot(sp.x - tap.x, sp.y - tap.y)
                        if (d < limit && d < best) { best = d; hit = i }
                    }
                    currentOnTap(geo, hit)
                    true
                } else if (!vm.navigating && vm.routes.size > 1) {
                    // Permite tocar direto numa rota alternativa cinza no mapa para selecioná-la
                    val picked = pickAlternativeRoute(m, geo, vm.routes.keys - vm.mode, vm)
                    if (picked != null) {
                        vm.selectMode(picked)
                        true
                    } else false
                } else {
                    false
                }
            }
            m.setStyle(Style.Builder().fromUri(MapLayers.STYLE_URL)) { s ->
                MapLayers.setup(s)
                map = m
                style = s
            }
        }
    }

    // Ponto azul / seta do GPS
    LaunchedEffect(style, hasLocationPermission) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        if (!hasLocationPermission) return@LaunchedEffect
        val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return@LaunchedEffect

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
    LaunchedEffect(style, vm.pois) {
        style?.let { MapLayers.updatePois(it, vm.pois) }
    }
    LaunchedEffect(style, vm.hazards) {
        style?.let { MapLayers.updateHazards(it, vm.hazards) }
    }
    LaunchedEffect(style, vm.risks) {
        style?.let { MapLayers.updateRisks(it, vm.risks) }
    }
    LaunchedEffect(style, vm.knownRisksVersion) {
        style?.let {
            MapLayers.updateKnownRisks(it, vm.known())
            MapLayers.updateCorridors(it, vm.corridors())
        }
    }
    LaunchedEffect(style, vm.origin, vm.stops, vm.destination) {
        style?.let { MapLayers.updateWaypoints(it, vm.origin, vm.stops, vm.destination) }
    }

    // Posição do GPS e controle de zoom dinâmico
    LaunchedEffect(style, vm.lastLocation) {
        val m = map ?: return@LaunchedEffect
        val loc = vm.lastLocation ?: return@LaunchedEffect
        if (m.locationComponent.isLocationComponentActivated) {
            m.locationComponent.forceLocationUpdate(Location(loc))
        }
        if (vm.navigating && vm.following) {
            val target = navZoom(vm.speedKmh, vm.navState?.distToHintM)
            if ((abs(target - m.cameraPosition.zoom) > 0.3 && abs(target - currentZoom) > 0.05) ||
                abs(target - m.cameraPosition.zoom) > 1.0
            ) {
                currentZoom = target
                if (m.locationComponent.isLocationComponentActivated) {
                    m.locationComponent.zoomWhileTracking(target, 2200)
                }
            }
            if (!m.locationComponent.isLocationComponentActivated) {
                m.easeCamera(CameraUpdateFactory.newLatLng(LatLng(loc.latitude, loc.longitude)), 1000, false)
            }
        } else if (!centeredOnce && vm.destination == null) {
            centeredOnce = true
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 14.2))
        }
    }

    // Entrar / sair do modo navegação (preservando posição/zoom atuais ao sair!)
    LaunchedEffect(style, vm.navigating) {
        val m = map ?: return@LaunchedEffect
        val lc = m.locationComponent
        if (vm.navigating) {
            wasNavigating = true
            currentZoom = navZoom(vm.speedKmh, vm.navState?.distToHintM)
            if (lc.isLocationComponentActivated) {
                follow(m, currentZoom)
            } else {
                vm.myLocation?.let {
                    m.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder().target(LatLng(it.lat, it.lon)).zoom(currentZoom).tilt(38.0).build()
                        )
                    )
                }
            }
        } else if (wasNavigating) {
            wasNavigating = false
            if (lc.isLocationComponentActivated) lc.cameraMode = CameraMode.NONE
            m.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
            val cur = m.cameraPosition
            m.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder(cur).tilt(0.0).bearing(0.0).build()
                ),
                600,
            )
        }
    }

    // "Re-centralizar": volta a seguir a moto
    LaunchedEffect(vm.recenterRequest) {
        if (vm.recenterRequest == 0 || !vm.navigating) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        currentZoom = navZoom(vm.speedKmh, vm.navState?.distToHintM)
        if (m.locationComponent.isLocationComponentActivated) {
            follow(m, currentZoom)
        }
    }

    // "Geral": mostra o que falta da rota inteira, visto de cima
    LaunchedEffect(vm.overviewRequest) {
        if (vm.overviewRequest == 0 || !vm.navigating) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val r = vm.currentRoute ?: return@LaunchedEffect
        val from = vm.navState?.progressIndex ?: 0
        val pts = r.points.subList(from.coerceIn(0, r.points.size - 1), r.points.size)
        if (pts.size < 2) return@LaunchedEffect
        if (m.locationComponent.isLocationComponentActivated) m.locationComponent.cameraMode = CameraMode.NONE
        m.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
        val b = LatLngBounds.Builder()
        pts.forEach { b.include(LatLng(it.lat, it.lon)) }
        val side = with(density) { 44.dp.toPx().toInt() }
        val top = with(density) { 180.dp.toPx().toInt() }
        val bottom = with(density) { 210.dp.toPx().toInt() }
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 0.0, 0.0, side, top, side, bottom), 850)
    }

    // Navegando: linha azul mostra só o trecho restante
    LaunchedEffect(style, vm.navState) {
        val s = style ?: return@LaunchedEffect
        val st = vm.navState ?: return@LaunchedEffect
        val r = vm.currentRoute ?: return@LaunchedEffect
        if (!vm.navigating) return@LaunchedEffect
        MapLayers.updateRemaining(s, r, st.progressIndex, st.snapped ?: return@LaunchedEffect)
    }

    // Enquadrar a rota inteira no planejamento
    LaunchedEffect(vm.fitRequest, style) {
        val m = map ?: return@LaunchedEffect
        if (vm.navigating) return@LaunchedEffect
        val r = vm.currentRoute ?: return@LaunchedEffect
        if (r.points.size < 2) return@LaunchedEffect
        val b = LatLngBounds.Builder()
        r.points.forEach { b.include(LatLng(it.lat, it.lon)) }
        val side = with(density) { 44.dp.toPx().toInt() }
        val top = with(density) { 110.dp.toPx().toInt() }
        val bottom = with(density) { (bottomPaddingDp + 32).dp.toPx().toInt() }
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 0.0, 0.0, side, top, side, bottom), 750)
    }

    // Botão "Centralizar em mim"
    LaunchedEffect(vm.centerRequest) {
        if (vm.centerRequest == 0) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val p = vm.myLocation ?: return@LaunchedEffect
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 15.2))
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun pickAlternativeRoute(m: MapLibreMap, tap: GeoPoint, altModes: Set<Mode>, vm: AppViewModel): Mode? {
    val metersPerPx = m.projection.getMetersPerPixelAtLatitude(tap.lat)
    val maxDistM = (metersPerPx * 28.0).coerceIn(25.0, 2500.0)
    // Não troca se o toque também estiver colado na rota atual
    val curPts = vm.currentRoute?.points
    if (curPts != null && minRouteDist(curPts, tap) < maxDistM * 0.6) return null

    var bestMode: Mode? = null
    var bestD = maxDistM
    for (mode in altModes) {
        val pts = vm.routes[mode]?.points ?: continue
        val d = minRouteDist(pts, tap)
        if (d < bestD) {
            bestD = d
            bestMode = mode
        }
    }
    return bestMode
}

private fun minRouteDist(pts: List<GeoPoint>, p: GeoPoint): Double {
    val k = kotlin.math.cos(Math.toRadians(p.lat))
    var best = Double.MAX_VALUE
    val step = (pts.size / 600).coerceAtLeast(1)
    var i = 0
    while (i < pts.size - step) {
        val a = pts[i]
        val b = pts[i + step]
        val ax = (a.lon - p.lon) * k * 111320.0
        val ay = (a.lat - p.lat) * 110540.0
        val bx = (b.lon - p.lon) * k * 111320.0
        val by = (b.lat - p.lat) * 110540.0
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 > 0) (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0) else 0.0
        val d = hypot(ax + dx * t, ay + dy * t)
        if (d < best) best = d
        i += step
    }
    return best
}

private fun follow(m: MapLibreMap, zoom: Double) {
    val lc = m.locationComponent
    val h = m.height.toDouble().takeIf { it > 0 } ?: 1600.0
    // visão estilo Waze: seta um pouco abaixo do meio, pouca inclinação, enxerga mais à frente e dos lados
    m.moveCamera(CameraUpdateFactory.paddingTo(0.0, h * 0.30, 0.0, h * 0.12))
    lc.cameraMode = CameraMode.TRACKING_GPS
    lc.tiltWhileTracking(38.0)
    lc.zoomWhileTracking(zoom)
}

private fun navZoom(speedKmh: Int, distToManeuverM: Double?): Double {
    // mais aberto (estilo Waze): cidade ~16,6; estrada rápida ~15,4; chegando na curva aproxima um pouco
    val base = 16.6 - ((speedKmh - 30).coerceIn(0, 80) / 80.0) * 1.2
    val nearTurn = distToManeuverM != null && distToManeuverM < 300
    return (base + if (nearTurn) 0.6 else 0.0).coerceAtMost(17.3)
}

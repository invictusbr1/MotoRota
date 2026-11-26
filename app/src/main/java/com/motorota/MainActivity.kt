package com.motorota

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.motorota.nav.NavigationBridge
import com.motorota.routing.GeoPoint
import com.motorota.routing.Waypoint
import com.motorota.ui.MainScreen
import com.motorota.ui.MotoRotaTheme
import com.motorota.ui.SplashScreen
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private val hasPermission = mutableStateOf(false)
    private var locationManager: LocationManager? = null

    private val listener = LocationListener { loc: Location -> vm.onLocation(loc) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        hasPermission.value = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (hasPermission.value) startGps()
        vm.jarvis.setForeground(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        // Guarda até 600 MB do mapa visual já visto, para funcionar com sinal fraco
        OfflineManager.getInstance(this).setMaximumAmbientCacheSize(600L * 1024 * 1024, null)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        hasPermission.value = hasAnyLocationPermission()

        // Apenas na versão de teste (primeira criação): am start --es partida "lat,lon" --es destino "lat,lon"
        if (BuildConfig.DEBUG && savedInstanceState == null) {
            fun pt(k: String): GeoPoint? = intent?.getStringExtra(k)?.split(",")?.takeIf { it.size >= 2 }?.let {
                val lat = it[0].trim().toDoubleOrNull() ?: return@let null
                val lon = it[1].trim().toDoubleOrNull() ?: return@let null
                GeoPoint(lat, lon)
            }
            val o = pt("partida")
            val d = pt("destino")
            if (d != null) {
                if (o != null) vm.setOriginHere(Waypoint(o, "Partida (teste)"))
                vm.goTo(Waypoint(d, "Destino (teste)"))
            }
        }

        setContent {
            MotoRotaTheme {
                // Mantém a tela ligada enquanto navega
                LaunchedEffect(vm.navigating) {
                    if (vm.navigating) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                MainScreen(vm, hasPermission.value)

                var showSplash by rememberSaveable { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(1400)
                    showSplash = false
                }
                AnimatedVisibility(
                    visible = showSplash,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) { SplashScreen() }
            }
        }

        val prefs = getSharedPreferences("preferencias", MODE_PRIVATE)
        val askMic = !vm.jarvis.hasMic() && !prefs.getBoolean("pediu_microfone", false)
        if (!hasPermission.value || askMic) {
            val perms = buildList {
                if (!hasPermission.value) {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if (askMic) add(Manifest.permission.RECORD_AUDIO)
            }
            if (askMic) prefs.edit().putBoolean("pediu_microfone", true).apply()
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    override fun onStart() {
        super.onStart()
        hasPermission.value = hasAnyLocationPermission()
        if (hasPermission.value && !NavigationBridge.running) startGps()
    }

    override fun onStop() {
        super.onStop()
        // Quando está navegando, o NavigationService mantém o GPS ativo em 1º plano
        locationManager?.removeUpdates(listener)
    }

    override fun onDestroy() {
        locationManager?.removeUpdates(listener)
        super.onDestroy()
    }

    private fun hasAnyLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startGps() {
        val lm = locationManager ?: return
        try {
            lm.removeUpdates(listener)
            val bestLast = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
                ?: runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            if (bestLast != null && vm.myLocation == null) vm.onLocation(bestLast)

            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            hasPermission.value = false
        }
    }
}

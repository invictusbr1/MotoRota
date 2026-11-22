package com.motorota

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.motorota.ui.MainScreen
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
        hasPermission.value = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasPermission.value) startGps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        // guarda ate 600 MB do mapa visual ja visto, para funcionar com sinal fraco
        OfflineManager.getInstance(this).setMaximumAmbientCacheSize(600L * 1024 * 1024, null)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        hasPermission.value = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF8AB4F8),
                    surface = Color(0xFF1A1E22),
                    background = Color(0xFF101214),
                )
            ) {
                // mantem a tela ligada enquanto navega
                LaunchedEffect(vm.navigating) {
                    if (vm.navigating) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                MainScreen(vm, hasPermission.value)
                // abertura com a logo do Grupo Conecta
                var showSplash by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(1800); showSplash = false }
                androidx.compose.animation.AnimatedVisibility(
                    visible = showSplash,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                ) { com.motorota.ui.SplashScreen() }
            }
        }

        if (!hasPermission.value) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasPermission.value) startGps()
    }

    override fun onStop() {
        super.onStop()
        // navegando, o GPS continua mesmo com a tela apagada por instantes
        if (!vm.navigating) locationManager?.removeUpdates(listener)
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        val lm = locationManager ?: return
        try {
            lm.removeUpdates(listener)
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { vm.onLocation(it) }
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { vm.onLocation(it) }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, listener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            hasPermission.value = false
        }
    }
}

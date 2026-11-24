package com.motorota.nav

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.motorota.MainActivity
import com.motorota.R

/**
 * Ponte simples entre o serviço de navegação em 1º plano e o ViewModel,
 * garantindo que o GPS continue funcionando com a tela apagada (no bolso / intercomunicador).
 */
object NavigationBridge {
    @Volatile var onLocation: ((Location) -> Unit)? = null
    @Volatile var onStopRequested: (() -> Unit)? = null
    @Volatile var running: Boolean = false
}

class NavigationService : Service() {

    private var locationManager: LocationManager? = null
    private val listener = LocationListener { loc ->
        NavigationBridge.onLocation?.invoke(loc)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                NavigationBridge.onStopRequested?.invoke()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Navegação ativa"
                val sub = intent.getStringExtra(EXTRA_SUBTITLE) ?: "Guiando pelo MotoRota"
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification(title, sub))
                return START_NOT_STICKY
            }
        }

        val notif = buildNotification(
            intent?.getStringExtra(EXTRA_TITLE) ?: "Navegação ativa",
            intent?.getStringExtra(EXTRA_SUBTITLE) ?: "Acompanhando sua rota por GPS",
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            NavigationBridge.running = true
            startGps()
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NavigationBridge.running = false
        try {
            locationManager?.removeUpdates(listener)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        val lm = locationManager ?: return
        try {
            lm.removeUpdates(listener)
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Navegação em andamento",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mantém o GPS e os avisos por voz ativos com a tela bloqueada"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, subtitle: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NavigationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(openIntent)
            .addAction(0, "Encerrar", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "motorota_nav"
        private const val NOTIF_ID = 1001
        private const val ACTION_STOP = "com.motorota.action.STOP_NAV"
        private const val ACTION_UPDATE = "com.motorota.action.UPDATE_NAV"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"

        fun start(ctx: Context, title: String, subtitle: String) {
            val i = Intent(ctx, NavigationService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SUBTITLE, subtitle)
            runCatching { ctx.startForegroundService(i) }
        }

        fun update(ctx: Context, title: String, subtitle: String) {
            if (!NavigationBridge.running) return
            val i = Intent(ctx, NavigationService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
            }
            runCatching { ctx.startService(i) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, NavigationService::class.java)) }
        }
    }
}

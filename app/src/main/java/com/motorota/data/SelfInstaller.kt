package com.motorota.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * O MotoRota baixa e instala a própria atualização pelo instalador oficial do Android
 * (PackageInstaller). App instalado assim não sofre o bloqueio de "configurações restritas",
 * então Acessibilidade (envio automático no WhatsApp) e leitura de mensagens podem ser ativadas.
 */
object SelfInstaller {

    /** true se o MotoRota já pode instalar apps; se não, abre a tela para o piloto permitir. */
    fun ensureAllowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()) return true
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return false
    }

    /** Baixa o APK e manda instalar (o Android mostra a confirmação). progress: 0..100 */
    suspend fun install(ctx: Context, apkUrl: String, progress: (Int) -> Unit): String = withContext(Dispatchers.IO) {
        val file = File(ctx.cacheDir, "atualizacao.apk")
        try {
            var conn = URL(apkUrl).openConnection() as HttpURLConnection
            var hops = 0
            while (true) {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                val code = conn.responseCode
                if (code in 300..399 && hops < 6) {
                    val next = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    conn = URL(URL(conn.url, next).toString()).openConnection() as HttpURLConnection
                    hops++
                    continue
                }
                break
            }
            if (conn.responseCode !in 200..299) return@withContext "Não consegui baixar (erro ${conn.responseCode})."
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 60_000_000L
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress((done * 100 / total).toInt().coerceIn(0, 99))
                    }
                }
            }
            conn.disconnect()

            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResult::class.java), flags)
                s.commit(pending.intentSender)
            }
            progress(100)
            "Instalando… confirme na tela do Android."
        } catch (e: Exception) {
            Log.w("MotoRota", "Autoinstalação: $e")
            "Não consegui instalar: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}

/** Resposta do instalador: se pedir confirmação, abre a tela do Android. */
class InstallResult : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Log.w("MotoRota", "Autoinstalação falhou: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}

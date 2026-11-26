package com.motorota.assistant

import android.Manifest
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import java.text.Normalizer

/**
 * O que o Jarvis consegue fazer nos outros aplicativos do celular:
 * abrir apps, ligar, mandar WhatsApp, controlar música e volume, ler mensagens que chegam.
 */
class PhoneActions(private val ctx: Context) {

    class NeedPermission(val permissions: List<String>, message: String) : Exception(message)

    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun norm(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    private fun start(i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Traz o MotoRota de volta para a frente depois de uma ação que abriu outro app
     * (o piloto não precisa tocar no celular). Funciona porque o app tem o acesso a notificações
     * / acessibilidade ligado, que o Android deixa abrir a tela mesmo em segundo plano.
     */
    fun returnToApp(delayMs: Long) {
        val home = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { ctx.startActivity(home) } }, delayMs)
    }

    // ---------- Aplicativos ----------

    data class AppInfo(val label: String, val pkg: String)

    fun installedApps(): List<AppInfo> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map {
            AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName)
        }.distinctBy { it.pkg }
    }

    fun openApp(name: String): String {
        val q = norm(name)
        val apps = installedApps()
        val app = apps.firstOrNull { norm(it.label) == q }
            ?: apps.firstOrNull { norm(it.label).startsWith(q) }
            ?: apps.firstOrNull { norm(it.label).contains(q) || q.contains(norm(it.label)) }
            ?: return "Não achei o aplicativo \"$name\" no celular."
        val launch = ctx.packageManager.getLaunchIntentForPackage(app.pkg) ?: return "Não consegui abrir o ${app.label}."
        return if (start(launch)) "Abrindo o ${app.label}." else "Não consegui abrir o ${app.label}."
    }

    // ---------- Contatos e ligações ----------

    data class Contact(val name: String, val number: String)

    fun findContact(spoken: String): Contact? {
        if (!has(Manifest.permission.READ_CONTACTS)) {
            throw NeedPermission(listOf(Manifest.permission.READ_CONTACTS), "Preciso de acesso aos contatos. Aceite o pedido na tela e me peça de novo.")
        }
        val q = norm(spoken).removePrefix("o ").removePrefix("a ").removePrefix("minha ").removePrefix("meu ").trim()
        val list = ArrayList<Contact>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                list.add(Contact(n, num))
            }
        }
        return list.firstOrNull { norm(it.name) == q }
            ?: list.firstOrNull { norm(it.name).startsWith(q) }
            ?: list.firstOrNull { c -> norm(c.name).split(' ').any { it.isNotEmpty() && it == q } }
            ?: list.firstOrNull { norm(it.name).contains(q) }
    }

    fun call(spoken: String): String {
        val c = findContact(spoken) ?: return "Não achei \"$spoken\" nos seus contatos."
        if (!has(Manifest.permission.CALL_PHONE)) {
            throw NeedPermission(listOf(Manifest.permission.CALL_PHONE), "Preciso da permissão de fazer ligações. Aceite na tela e me peça de novo.")
        }
        val ok = start(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(c.number))))
        if (ok) returnToApp(3500) // a ligação continua; a tela volta para o mapa
        return if (ok) "Ligando para ${c.name}." else "Não consegui ligar para ${c.name}."
    }

    /** Abre a conversa do WhatsApp com a mensagem já escrita (o envio é um toque, ou automático com a Acessibilidade). */
    fun whatsapp(spoken: String, message: String): String {
        val c = findContact(spoken) ?: return "Não achei \"$spoken\" nos seus contatos."
        var digits = c.number.filter { it.isDigit() }
        if (digits.startsWith("0")) digits = digits.trimStart('0')
        if (digits.length in 10..11) digits = "55$digits" // número brasileiro sem o código do país
        val uri = Uri.parse("https://wa.me/$digits?text=" + Uri.encode(message))
        val i = Intent(Intent.ACTION_VIEW, uri)
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { isInstalled(it) }
        if (pkg != null) i.setPackage(pkg)
        if (!start(i)) return "Não consegui abrir o WhatsApp."
        WhatsAppSender.armFor(message)
        return if (WhatsAppSender.enabled(ctx)) "Mandando para ${c.name} no WhatsApp."
        else "Abri a conversa com ${c.name} com a mensagem pronta; é só tocar em enviar. Para eu enviar sozinho, ative o MotoRota em Acessibilidade."
    }

    private fun isInstalled(pkg: String) = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) {
        false
    }

    // ---------- Música e volume ----------

    private val am get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun mediaKey(code: Int) {
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    /** Players de música ativos (Spotify primeiro). Precisa do acesso a notificações. */
    private fun players(): List<android.media.session.MediaController> {
        if (!notificationsAllowed()) return emptyList()
        return try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
            msm.getActiveSessions(ComponentName(ctx, MessageListener::class.java))
                .filter { it.packageName != ctx.packageName }
                .sortedByDescending { it.packageName == SPOTIFY }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun canSearch(c: android.media.session.MediaController) =
        ((c.playbackState?.actions ?: 0L) and android.media.session.PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0L

    fun music(action: String, query: String?): String {
        val a = norm(action)
        if (!query.isNullOrBlank()) return playSearch(query)
        val p = players().firstOrNull()?.transportControls
        return when {
            a.contains("pausa") || a.contains("para") -> { p?.pause() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); "Música pausada." }
            a.contains("proxim") || a.contains("pula") || a.contains("avanca") -> { p?.skipToNext() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); "Próxima." }
            a.contains("anterior") || a.contains("volta") -> { p?.skipToPrevious() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); "Anterior." }
            else -> { p?.play() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); "Tocando." }
        }
    }

    /**
     * Toca pelo nome SEM abrir o app: manda o pedido direto para o player que já está aberto
     * em segundo plano. Se o Spotify estiver fechado, abre, dá o play e volta para o MotoRota sozinho.
     */
    private fun playSearch(q: String): String {
        val before = players()
        before.firstOrNull { canSearch(it) }?.let { c ->
            c.transportControls.playFromSearch(q, android.os.Bundle())
            confirmPlaying(q, 0)
            return "Tocando $q."
        }
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, q)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (isInstalled(SPOTIFY)) i.setPackage(SPOTIFY)
        if (!start(i)) return "Não achei um app de música que aceite pedido por voz."
        // o Spotify frio às vezes abre sem tocar: vai conferindo e insiste no play pela sessão dele
        confirmPlaying(q, 1)
        returnToApp(6000)
        return "Tocando $q."
    }

    /** Confere de 1,5 em 1,5 s se começou a tocar; senão manda de novo (pela sessão ou tecla de play). */
    private fun confirmPlaying(q: String, attempt: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val list = players()
            val c = list.firstOrNull { it.packageName == SPOTIFY } ?: list.firstOrNull()
            val st = c?.playbackState?.state
            if (st == android.media.session.PlaybackState.STATE_PLAYING ||
                st == android.media.session.PlaybackState.STATE_BUFFERING
            ) return@postDelayed
            if (attempt >= 5) {
                com.motorota.data.Http.report(
                    "jarvis-musica",
                    "nao tocou \"$q\" notif=${notificationsAllowed()} sessoes=${list.map { "${it.packageName}:${it.playbackState?.state}:${it.playbackState?.actions}" }}",
                )
                return@postDelayed
            }
            runCatching {
                when {
                    c != null && canSearch(c) -> c.transportControls.playFromSearch(q, android.os.Bundle())
                    c != null -> c.transportControls.play()
                    attempt >= 3 -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                    else -> {}
                }
            }
            confirmPlaying(q, attempt + 1)
        }, 1500)
    }

    fun volume(action: String, percent: Int? = null): String {
        val a = norm(action)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (percent != null && percent in 0..100) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(percent / 100f * max), 0)
            return "Volume em $percent por cento."
        }
        if (a.contains("mud") || a.contains("silenc") || a.contains("zer")) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            return "Som da música zerado."
        }
        val dir = when {
            a.contains("aument") || a.contains("sobe") || a.contains("mais") -> AudioManager.ADJUST_RAISE
            a.contains("diminu") || a.contains("abaix") || a.contains("menos") -> AudioManager.ADJUST_LOWER
            a.contains("max") -> {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
                return "Volume no máximo."
            }
            else -> AudioManager.ADJUST_RAISE
        }
        repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0) }
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return "Volume em $v por cento."
    }

    companion object {
        private const val SPOTIFY = "com.spotify.music"
    }

    // ---------- Mensagens que chegam ----------

    fun notificationsAllowed(): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        return flat.contains(ComponentName(ctx, MessageListener::class.java).flattenToString())
    }

    fun readMessages(content: Boolean, contact: String?): String {
        if (!notificationsAllowed()) {
            openNotificationSettings()
            return "Para eu ver suas mensagens, ative o MotoRota em Acesso a notificações. Abri a tela para você."
        }
        var list = MessageListener.unread()
        if (contact != null) {
            val q = norm(contact)
            list = list.filter { norm(it.from).contains(q) || q.contains(norm(it.from)) }
        }
        if (list.isEmpty()) return if (contact != null) "Nenhuma mensagem nova de $contact." else "Nenhuma mensagem nova."
        if (!content) {
            val who = list.groupBy { it.from }.map { (from, msgs) ->
                if (msgs.size > 1) "$from (${msgs.size}, ${msgs.first().app})" else "$from (${msgs.first().app})"
            }
            return "Mensagens novas de: " + who.joinToString(", ") + "."
        }
        MessageListener.markRead(list)
        return list.takeLast(6).joinToString(" ") { "${it.from} no ${it.app}: ${it.text}." }
    }

    fun openNotificationSettings() {
        start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openAccessibilitySettings() {
        start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

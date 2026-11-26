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

    fun music(action: String, query: String?): String {
        val a = norm(action)
        return when {
            !query.isNullOrBlank() -> playSearch(query)
            a.contains("pausa") || a.contains("para") -> { mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); "Música pausada." }
            a.contains("proxim") || a.contains("pula") || a.contains("avanca") -> { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); "Próxima." }
            a.contains("anterior") || a.contains("volta") -> { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); "Anterior." }
            else -> { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); "Tocando." }
        }
    }

    private fun playSearch(q: String): String {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, q)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (isInstalled("com.spotify.music")) i.setPackage("com.spotify.music")
        return if (start(i)) "Tocando $q." else "Não achei um app de música que aceite pedido por voz."
    }

    fun volume(action: String): String {
        val a = norm(action)
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

    // ---------- Mensagens que chegam ----------

    fun notificationsAllowed(): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        return flat.contains(ComponentName(ctx, MessageListener::class.java).flattenToString())
    }

    fun readMessages(): String {
        if (!notificationsAllowed()) {
            openNotificationSettings()
            return "Para eu ler suas mensagens, ative o MotoRota em Acesso a notificações. Abri a tela para você."
        }
        val list = MessageListener.unread()
        if (list.isEmpty()) return "Nenhuma mensagem nova."
        MessageListener.markRead()
        return list.takeLast(6).joinToString(" ") { "${it.app}, ${it.from}: ${it.text}." }
    }

    fun openNotificationSettings() {
        start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openAccessibilitySettings() {
        start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

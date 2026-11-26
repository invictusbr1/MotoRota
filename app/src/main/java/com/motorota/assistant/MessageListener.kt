package com.motorota.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Recebe as notificações de mensagens (WhatsApp, Telegram, SMS) para o Jarvis ler em voz alta.
 * Só funciona depois que o piloto ativa o MotoRota em "Acesso a notificações".
 * Nada sai do celular: as mensagens ficam só na memória do app.
 */
class MessageListener : NotificationListenerService() {

    data class Msg(val app: String, val from: String, val text: String, val time: Long, var read: Boolean = false)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = APPS[sbn.packageName] ?: return
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val ex = n.extras ?: return
        val from = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (from.isEmpty() || text.isEmpty()) return
        // resumos como "5 novas mensagens" não interessam
        if (Regex("^\\d+ (novas )?mensage").containsMatchIn(text.lowercase())) return
        val m = Msg(app, from, text, System.currentTimeMillis())
        synchronized(list) {
            if (list.any { it.from == m.from && it.text == m.text && m.time - it.time < 60_000 }) return
            list.add(m)
            while (list.size > 30) list.removeAt(0)
        }
        onNew?.invoke(m)
    }

    companion object {
        private val APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram",
            "com.google.android.apps.messaging" to "SMS",
            "com.samsung.android.messaging" to "SMS",
        )
        private val list = ArrayList<Msg>()

        /** chamado a cada mensagem nova (o app decide se lê em voz alta) */
        @Volatile var onNew: ((Msg) -> Unit)? = null

        fun unread(): List<Msg> = synchronized(list) { list.filter { !it.read } }

        fun markRead() = synchronized(list) { list.forEach { it.read = true } }

        fun markRead(which: List<Msg>) = synchronized(list) { which.forEach { it.read = true } }
    }
}

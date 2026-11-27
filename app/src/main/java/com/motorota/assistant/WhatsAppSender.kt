package com.motorota.assistant

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Serviço de Acessibilidade usado só para uma coisa: quando o Jarvis abre o WhatsApp com a
 * mensagem pronta, ele toca no botão "Enviar" (assim o piloto não precisa mexer no celular).
 * Só age nos 15 segundos depois de o Jarvis preparar uma mensagem, e só no WhatsApp.
 */
class WhatsAppSender : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        if (System.currentTimeMillis() > armedUntil) return
        val root = rootInActiveWindow ?: return
        val send = findSend(root, pkg)
        if (send == null) {
            // ainda carregando a conversa: tenta de novo no próximo evento; se não achar em 8 s, registra para corrigir
            if (!reported && System.currentTimeMillis() - armedAt > 8_000) {
                reported = true
                com.motorota.data.Http.report("whatsapp", "botão enviar não encontrado. ids: " + ids(root).take(40).joinToString(","))
            }
            return
        }
        if (click(send)) {
            armedUntil = 0L
            com.motorota.data.Http.report("whatsapp", "enviado (${System.currentTimeMillis() - armedAt} ms)")
            val home = packageManager.getLaunchIntentForPackage(packageName)
            if (home != null) {
                home.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { startActivity(home) } }, 900)
            }
        }
    }

    /** Acha o botão de enviar por id ("send", "conversation_send") ou pela descrição ("Enviar"/"Send"). */
    private fun findSend(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        for (id in listOf("send", "conversation_send", "send_container")) {
            root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull()?.let { return it }
        }
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var n = 0
        while (stack.isNotEmpty() && n < 2000) {
            val node = stack.removeFirst(); n++
            val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
            val vid = node.viewIdResourceName.orEmpty()
            if (desc == "enviar" || desc == "send" || vid.endsWith(":id/send")) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    /** toca no botão (ou no "pai" clicável dele) */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        repeat(4) {
            val c = cur ?: return false
            if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = c.parent
        }
        return false
    }

    private fun ids(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(root)
        while (stack.isNotEmpty() && out.size < 60) {
            val node = stack.removeFirst()
            (node.viewIdResourceName?.substringAfter(":id/") ?: node.contentDescription?.toString())?.let { out.add(it) }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        }
        return out
    }

    override fun onInterrupt() {}

    companion object {
        @Volatile private var armedUntil = 0L

        @Volatile private var armedAt = 0L
        @Volatile private var reported = false

        fun armFor(@Suppress("UNUSED_PARAMETER") message: String) {
            armedAt = System.currentTimeMillis()
            reported = false
            armedUntil = System.currentTimeMillis() + 20_000
        }

        fun enabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return flat.contains(ComponentName(ctx, WhatsAppSender::class.java).flattenToString())
        }
    }
}

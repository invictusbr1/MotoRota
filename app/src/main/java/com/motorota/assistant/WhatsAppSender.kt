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
        val send = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull()
            ?: root.findAccessibilityNodeInfosByText("Enviar").firstOrNull { it.isClickable }
            ?: return
        if (send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            armedUntil = 0L
            // volta para o MotoRota depois de enviar
            val home = packageManager.getLaunchIntentForPackage(packageName)
            if (home != null) {
                home.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { startActivity(home) } }, 800)
            }
        }
    }

    override fun onInterrupt() {}

    companion object {
        @Volatile private var armedUntil = 0L

        fun armFor(@Suppress("UNUSED_PARAMETER") message: String) {
            armedUntil = System.currentTimeMillis() + 15_000
        }

        fun enabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return flat.contains(ComponentName(ctx, WhatsAppSender::class.java).flattenToString())
        }
    }
}

package com.motorota.intercom

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Conexão permanente com a central (Supabase Realtime, protocolo Phoenix) por WebSocket.
 * Cada piloto escuta o próprio canal "piloto:<id>"; uma chamada usa o canal "chamada:<id>".
 * As mensagens chegam na thread principal.
 */
class Realtime(
    private val baseUrl: String,
    private val apiKey: String,
    private val onMessage: (channel: String, event: String, payload: JSONObject) -> Unit,
    private val onOnline: (Boolean) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private val ref = AtomicInteger(1)

    @Volatile private var ws: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var active = false

    /** canais que queremos ouvir (reentra neles ao reconectar) */
    private val wanted = ConcurrentHashMap.newKeySet<String>()
    /** canal -> join_ref atual */
    private val joined = ConcurrentHashMap<String, String>()

    val isOnline: Boolean get() = connected

    fun connect() {
        active = true
        if (ws != null) return
        val url = baseUrl.replace("https://", "wss://") + "/realtime/v1/websocket?apikey=$apiKey&vsn=1.0.0"
        ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    fun close() {
        active = false
        main.removeCallbacksAndMessages(null)
        ws?.close(1000, "tchau")
        ws = null
        connected = false
        joined.clear()
    }

    fun join(channel: String) {
        wanted.add(channel)
        if (connected && !joined.containsKey(channel)) sendJoin(channel)
    }

    fun leave(channel: String) {
        wanted.remove(channel)
        val jr = joined.remove(channel) ?: return
        push(channel, "phx_leave", JSONObject(), jr)
    }

    /** Envia um evento para quem está no canal (precisa ter entrado nele com join). */
    fun broadcast(channel: String, event: String, payload: JSONObject): Boolean {
        val jr = joined[channel] ?: return false
        val inner = JSONObject().put("type", "broadcast").put("event", event).put("payload", payload)
        return push(channel, "broadcast", inner, jr)
    }

    private fun sendJoin(channel: String) {
        val jr = ref.getAndIncrement().toString()
        joined[channel] = jr
        val config = JSONObject()
            .put("broadcast", JSONObject().put("self", false).put("ack", false))
            .put("presence", JSONObject().put("key", ""))
            .put("private", false)
        push(channel, "phx_join", JSONObject().put("config", config).put("access_token", apiKey), jr)
    }

    private fun push(channel: String, event: String, payload: JSONObject, joinRef: String?): Boolean {
        val msg = JSONObject()
            .put("topic", "realtime:$channel")
            .put("event", event)
            .put("payload", payload)
            .put("ref", ref.getAndIncrement().toString())
        if (joinRef != null) msg.put("join_ref", joinRef)
        return ws?.send(msg.toString()) ?: false
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!connected) return
            ws?.send(
                JSONObject().put("topic", "phoenix").put("event", "heartbeat")
                    .put("payload", JSONObject()).put("ref", ref.getAndIncrement().toString()).toString(),
            )
            main.postDelayed(this, 25_000)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            main.post {
                connected = true
                joined.clear()
                wanted.forEach { sendJoin(it) }
                main.removeCallbacks(heartbeat)
                main.postDelayed(heartbeat, 25_000)
                onOnline(true)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (o.optString("event") != "broadcast") return
            val topic = o.optString("topic").removePrefix("realtime:")
            val p = o.optJSONObject("payload") ?: return
            val ev = p.optString("event")
            val inner = p.optJSONObject("payload") ?: JSONObject()
            main.post { onMessage(topic, ev, inner) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped()

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w("MotoRota", "Intercom: conexão caiu: $t")
            dropped()
        }
    }

    private fun dropped() {
        main.post {
            ws = null
            val was = connected
            connected = false
            joined.clear()
            main.removeCallbacks(heartbeat)
            if (was) onOnline(false)
            if (active) main.postDelayed({ if (active && ws == null) connect() }, 3000)
        }
    }
}

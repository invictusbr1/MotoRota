package com.motorota.intercom

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

data class Friend(val id: String, val name: String)

enum class CallState { IDLE, CALLING, RINGING, IN_CALL }

/**
 * Intercomunicador pela internet entre pilotos que usam o MotoRota.
 *
 * Cada celular tem um id secreto e um código de 6 dígitos (que você passa para os amigos).
 * A chamada: quem liga manda "ring" no canal do amigo; o Jarvis do amigo pergunta
 * "posso atender?"; se sim, os dois entram no canal da chamada e a voz vai comprimida
 * (só enquanto alguém fala) pela central.
 */
class Intercom(
    private val ctx: Context,
    private val scope: CoroutineScope,
    /** fala uma frase (Jarvis) e espera terminar */
    private val speak: suspend (String) -> Unit,
    /** pergunta em voz alta e ouve sim/não; null = não deu para ouvir */
    private val askYesNo: suspend (String) -> Boolean?,
    /** mensagem curta na tela */
    private val notify: (String) -> Unit,
    /** a voz da chamada vai começar / terminou (o Jarvis solta e retoma o microfone) */
    private val onAudioStart: () -> Unit,
    private val onAudioEnd: () -> Unit,
    /** cria o detector de "Jarvis" alimentado pelo microfone da chamada */
    private val wakeFeeder: () -> com.motorota.assistant.WakeWord.Feeder?,
) {
    private val prefs = ctx.getSharedPreferences("intercomunicador", Context.MODE_PRIVATE)
    private val myId: String = prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("id", it).apply()
    }
    private val secret: String = prefs.getString("segredo", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("segredo", it).apply()
    }

    val route = AudioRoute(ctx)

    var myName by mutableStateOf(prefs.getString("nome", "").orEmpty()); private set
    var myCode by mutableStateOf(prefs.getString("codigo", "").orEmpty()); private set
    var friends by mutableStateOf(loadFriends()); private set
    var online by mutableStateOf(false); private set

    var state by mutableStateOf(CallState.IDLE); private set
    var peerName by mutableStateOf(""); private set
    var callStartedAt by mutableStateOf(0L); private set
    var muted by mutableStateOf(false)
    /** alguém está falando agora (para piscar o ícone) */
    var peerTalking by mutableStateOf(false); private set

    private var peerId = ""
    private var callId = ""
    private var acked = false
    private var timeoutJob: Job? = null
    private var ringJob: Job? = null
    private var lastPeerAudio = 0L

    private val rt = Realtime(SUPABASE_URL, SUPABASE_KEY, ::onRealtime) { on ->
        online = on
    }

    val inCall: Boolean get() = state == CallState.IN_CALL
    val busy: Boolean get() = state != CallState.IDLE

    // ---------- Cadastro ----------

    /** Liga a conexão com a central (só depois de ter nome). */
    fun start() {
        if (myName.isBlank()) return
        rt.join(myChannel(myId))
        rt.connect()
        scope.launch { register() }
    }

    suspend fun setName(name: String): String {
        val n = name.trim().take(40)
        if (n.isEmpty()) return "Digite seu nome."
        myName = n
        prefs.edit().putString("nome", n).apply()
        val ok = register()
        start()
        return if (ok) "Pronto! Seu código é $myCode." else "Nome salvo. Sem internet para registrar agora; tento de novo depois."
    }

    private suspend fun register(): Boolean {
        val body = JSONObject().put("p_id", myId).put("p_segredo", secret).put("p_nome", myName)
        val (code, text) = rpc("registrar_piloto", body) ?: return false
        if (code !in 200..299) {
            Log.w("MotoRota", "Intercom: cadastro HTTP $code $text")
            return false
        }
        val c = text.trim().trim('"')
        if (c.length == 6) {
            myCode = c
            prefs.edit().putString("codigo", c).apply()
        }
        return true
    }

    suspend fun addFriend(codeTyped: String, nickname: String): String {
        val code = codeTyped.filter { it.isDigit() }
        if (code.length != 6) return "O código tem 6 números."
        if (code == myCode) return "Esse é o seu próprio código."
        val (status, text) = rpc("buscar_piloto", JSONObject().put("p_codigo", code)) ?: return "Sem internet agora."
        if (status !in 200..299) return "Não consegui consultar (erro $status)."
        val arr = runCatching { JSONArray(text) }.getOrNull()
        val o = arr?.optJSONObject(0) ?: return "Nenhum piloto com esse código."
        val id = o.optString("id")
        val name = nickname.trim().ifBlank { o.optString("nome") }.take(40)
        friends = friends.filter { it.id != id } + Friend(id, name)
        saveFriends()
        return "$name adicionado. Diga \"Jarvis, chama o $name\"."
    }

    fun removeFriend(id: String) {
        friends = friends.filter { it.id != id }
        saveFriends()
    }

    /** Acha o amigo pelo nome falado ("André", "o andre", "Dé"...). */
    fun findFriend(spoken: String): Friend? {
        val s = norm(spoken).removePrefix("o ").removePrefix("a ").trim()
        if (s.isEmpty()) return null
        friends.firstOrNull { norm(it.name) == s }?.let { return it }
        friends.firstOrNull { norm(it.name).startsWith(s) || s.startsWith(norm(it.name)) }?.let { return it }
        return friends.firstOrNull { f -> norm(f.name).split(' ').any { it.length > 2 && s.contains(it) } }
    }

    private fun loadFriends(): List<Friend> {
        val arr = runCatching { JSONArray(prefs.getString("amigos", "[]")) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Friend(it.optString("id"), it.optString("nome")) }?.takeIf { it.id.isNotBlank() }
        }
    }

    private fun saveFriends() {
        val arr = JSONArray()
        friends.forEach { arr.put(JSONObject().put("id", it.id).put("nome", it.name)) }
        prefs.edit().putString("amigos", arr.toString()).apply()
    }

    // ---------- Chamada: quem liga ----------

    fun call(f: Friend): String {
        if (myName.isBlank()) return "Antes, coloque seu nome em Ajustes, Intercomunicador."
        if (busy) return "Já estou numa chamada."
        if (!hasMic()) return "Preciso da permissão do microfone."
        if (!online) {
            start()
            return "Estou sem conexão com a central agora. Confira a internet."
        }
        peerId = f.id
        peerName = f.name
        callId = UUID.randomUUID().toString()
        acked = false
        state = CallState.CALLING
        rt.join(myChannel(f.id))
        scope.launch {
            delay(400) // tempo de entrar no canal do amigo
            send(f.id, "ring", JSONObject().put("nome", myName))
        }
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(7000)
            if (state == CallState.CALLING && !acked) {
                finish("${f.name} está fora do ar. O app dele precisa estar aberto e com internet.", tellPeer = "cancel")
                return@launch
            }
            delay(33_000)
            if (state == CallState.CALLING) finish("${f.name} não atendeu.", tellPeer = "cancel")
        }
        return "Chamando ${f.name}."
    }

    // ---------- Chamada: quem recebe ----------

    private fun onRing(from: String, name: String, call: String) {
        if (busy) {
            rt.join(myChannel(from))
            scope.launch {
                delay(400)
                rt.broadcast(myChannel(from), "busy", JSONObject().put("to", from).put("from", myId).put("call", call))
            }
            return
        }
        peerId = from
        peerName = name.ifBlank { friends.firstOrNull { it.id == from }?.name ?: "Um piloto" }
        friends.firstOrNull { it.id == from }?.let { peerName = it.name }
        callId = call
        state = CallState.RINGING
        rt.join(myChannel(from))
        scope.launch {
            delay(400)
            send(from, "ringing", JSONObject())
        }
        ringJob?.cancel()
        ringJob = scope.launch { ringTone() }
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(35_000)
            if (state == CallState.RINGING) finish("Chamada de $peerName perdida.", tellPeer = "reject")
        }
        scope.launch {
            delay(600)
            if (state != CallState.RINGING) return@launch
            val yes = askYesNo("${myName.ifBlank { "Piloto" }}, o $peerName quer falar com você. Posso atender?")
            if (state != CallState.RINGING) return@launch
            when (yes) {
                true -> accept()
                false -> reject()
                null -> {} // continua tocando: dá para atender pelo botão ou chamando o Jarvis
            }
        }
    }

    fun accept() {
        if (state != CallState.RINGING) return
        if (!hasMic()) {
            notify("Preciso da permissão do microfone para atender.")
            return
        }
        send(peerId, "accept", JSONObject())
        startAudio()
    }

    fun reject() {
        if (state != CallState.RINGING) return
        finish("Chamada recusada.", tellPeer = "reject", speakIt = false)
    }

    /** Desliga, cancela ou recusa, o que fizer sentido agora. */
    fun hangUp() {
        when (state) {
            CallState.IDLE -> {}
            CallState.CALLING -> finish("Chamada cancelada.", tellPeer = "cancel", speakIt = false)
            CallState.RINGING -> reject()
            CallState.IN_CALL -> finish("Chamada encerrada.", tellPeer = "hangup", speakIt = false)
        }
    }

    // ---------- Mensagens da central ----------

    private fun onRealtime(channel: String, event: String, p: JSONObject) {
        if (channel.startsWith("chamada:")) {
            if (event == "a" && channel == callChannel() && p.optString("f") != myId) onAudio(p.optString("d"))
            return
        }
        if (p.optString("to") != myId) return // mensagem para outro piloto no mesmo canal
        val from = p.optString("from")
        val call = p.optString("call")
        when (event) {
            "ring" -> onRing(from, p.optString("nome"), call)
            "ringing" -> if (call == callId) acked = true
            "accept" -> if (call == callId && state == CallState.CALLING) startAudio()
            "reject" -> if (call == callId && state == CallState.CALLING) finish("$peerName não pode atender agora.")
            "busy" -> if (call == callId && state == CallState.CALLING) finish("$peerName está em outra chamada.")
            "cancel" -> if (call == callId && state == CallState.RINGING) finish("$peerName desistiu da chamada.", speakIt = false)
            "hangup" -> if (call == callId && state == CallState.IN_CALL) finish("$peerName desligou.")
        }
    }

    private fun send(to: String, event: String, extra: JSONObject) {
        extra.put("to", to).put("from", myId).put("call", callId)
        if (!rt.broadcast(myChannel(to), event, extra)) {
            // ainda entrando no canal: tenta de novo daqui a pouco
            scope.launch {
                delay(700)
                rt.broadcast(myChannel(to), event, extra)
            }
        }
    }

    private fun finish(msg: String, tellPeer: String? = null, speakIt: Boolean = true) {
        if (tellPeer != null && peerId.isNotBlank()) send(peerId, tellPeer, JSONObject())
        timeoutJob?.cancel()
        ringJob?.cancel()
        state = CallState.IDLE
        stopAudio()
        val oldPeer = peerId
        val oldCall = callId
        callStartedAt = 0L
        muted = false
        peerTalking = false
        scope.launch {
            delay(1500)
            if (oldPeer.isNotBlank() && oldPeer != myId && state == CallState.IDLE) rt.leave(myChannel(oldPeer))
            if (oldCall.isNotBlank()) rt.leave("chamada:$oldCall")
        }
        notify(msg)
        if (speakIt) scope.launch { speak(msg) }
    }

    // ---------- Áudio ----------

    @Volatile private var running = false
    @Volatile private var micPaused = false
    private var recThread: Thread? = null
    private var playThread: Thread? = null
    private val playQueue = LinkedBlockingQueue<ShortArray>()
    private var feeder: com.motorota.assistant.WakeWord.Feeder? = null

    private fun startAudio() {
        timeoutJob?.cancel()
        ringJob?.cancel()
        state = CallState.IN_CALL
        callStartedAt = SystemClock.elapsedRealtime()
        rt.join(callChannel())
        onAudioStart()
        route.acquire()
        feeder = wakeFeeder()
        running = true
        micPaused = false
        playQueue.clear()
        recThread = Thread(::recordLoop, "intercom-mic").also { it.start() }
        playThread = Thread(::playLoop, "intercom-som").also { it.start() }
        scope.launch {
            // pisca o "falando" do outro lado
            while (state == CallState.IN_CALL) {
                peerTalking = SystemClock.elapsedRealtime() - lastPeerAudio < 400
                delay(200)
            }
        }
        notify("Falando com $peerName. Diga \"Jarvis, desliga\" para encerrar.")
    }

    private fun stopAudio() {
        if (!running) return
        running = false
        recThread?.interrupt()
        playThread?.interrupt()
        runCatching { recThread?.join(400) }
        runCatching { playThread?.join(400) }
        recThread = null
        playThread = null
        feeder?.close()
        feeder = null
        route.release()
        onAudioEnd()
    }

    /** Solta o microfone um instante (para o Jarvis ouvir um comando no meio da chamada). */
    fun pauseMic() {
        micPaused = true
    }

    fun resumeMic() {
        micPaused = false
    }

    @SuppressLint("MissingPermission")
    private fun recordLoop() {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val frame = ShortArray(320) // 20 ms
        val out8k = ShortArray(800) // 100 ms a 8 kHz
        var outN = 0
        val st = Adpcm.State()
        var seq = 0
        var noise = 400.0
        var lastVoice = 0L
        var rec: AudioRecord? = null
        try {
            while (running) {
                if (micPaused) {
                    rec?.let { runCatching { it.stop() }; it.release() }
                    rec = null
                    Thread.sleep(100)
                    continue
                }
                if (rec == null) {
                    val fresh = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400),
                    )
                    if (fresh.state != AudioRecord.STATE_INITIALIZED) {
                        fresh.release()
                        Thread.sleep(500)
                        continue
                    }
                    fresh.startRecording()
                    rec = fresh
                }
                val r: AudioRecord = rec ?: continue
                val n = r.read(frame, 0, frame.size)
                if (n <= 0) continue
                feeder?.let { runCatching { it.feed(frame, n) } }

                // Detector de voz simples: só transmite quando tem alguém falando (economiza internet)
                var sum = 0.0
                for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
                val rms = sqrt(sum / n)
                noise = if (rms < noise) noise * 0.9 + rms * 0.1 else noise * 0.998 + rms * 0.002
                val now = SystemClock.elapsedRealtime()
                if (rms > maxOf(noise * 2.2, 350.0)) lastVoice = now
                val talking = now - lastVoice < 700 && !muted

                // 16 kHz -> 8 kHz (média de pares)
                var i = 0
                while (i + 1 < n) {
                    out8k[outN++] = ((frame[i] + frame[i + 1]) / 2).toShort()
                    i += 2
                    if (outN == out8k.size) {
                        if (talking) {
                            val data = Adpcm.encode(out8k, outN, st)
                            val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
                            rt.broadcast(callChannel(), "a", JSONObject().put("f", myId).put("s", seq++).put("d", b64))
                        } else {
                            st.prev = 0; st.index = 0
                        }
                        outN = 0
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w("MotoRota", "Intercom: microfone: $e")
        } finally {
            rec?.let { runCatching { it.stop() }; it.release() }
        }
    }

    private fun onAudio(b64: String) {
        if (b64.isEmpty() || !running) return
        val pcm = runCatching { Adpcm.decode(Base64.decode(b64, Base64.NO_WRAP)) }.getOrNull() ?: return
        lastPeerAudio = SystemClock.elapsedRealtime()
        if (playQueue.size > 8) playQueue.poll() // não deixa o atraso crescer
        playQueue.offer(pcm)
    }

    private fun playLoop() {
        val rate = 8000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(min, 6400) * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w("MotoRota", "Intercom: alto-falante: $e")
            return
        }
        try {
            track.play()
            while (running) {
                val pcm = playQueue.poll(300, TimeUnit.MILLISECONDS) ?: continue
                track.write(pcm, 0, pcm.size)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w("MotoRota", "Intercom: som: $e")
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private suspend fun ringTone() {
        val tg = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull() ?: return
        try {
            delay(4500) // depois da pergunta do Jarvis
            while (state == CallState.RINGING) {
                tg.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1200)
                delay(3000)
            }
        } finally {
            tg.release()
        }
    }

    // ---------- Utilidades ----------

    fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun myChannel(id: String) = "piloto:$id"
    private fun callChannel() = "chamada:$callId"

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    private suspend fun rpc(fn: String, body: JSONObject): Pair<Int, String>? = withContext(Dispatchers.IO) {
        val r = com.motorota.data.Http.request(
            "$SUPABASE_URL/rest/v1/rpc/$fn", "POST",
            mapOf("apikey" to SUPABASE_KEY, "Authorization" to "Bearer $SUPABASE_KEY"),
            body.toString(),
        )
        if (r.code < 0) {
            Log.w("MotoRota", "Intercom: central: ${r.reason()}")
            null
        } else r.code to r.text
    }

    fun release() {
        if (busy) hangUp()
        rt.close()
    }

    companion object {
        // Central do MotoRota (Supabase). Esta chave é pública por natureza (só permite as funções liberadas).
        const val SUPABASE_URL = "https://lhmfiqeinpaydxlfgxif.supabase.co"
        const val SUPABASE_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImxobWZpcWVpbnBheWR4bGZneGlmIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTU3MDM0NDksImV4cCI6MjExMTI3OTQ0OX0.KTuCvhK72_abWMYVaL0-MMnhgWYQACJ2tUawwjvHvtk"
    }
}

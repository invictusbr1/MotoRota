package com.motorota.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.motorota.data.Keys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

enum class JarvisStatus { OFF, PREPARING, WAITING, LISTENING, THINKING, SPEAKING }

/**
 * Assistente de voz: "Jarvis" (ou o botão do microfone) -> ouve a pergunta -> Gemini
 * decide e usa as ações do app -> responde falando.
 */
class Jarvis(
    private val ctx: Context,
    private val scope: CoroutineScope,
    tools: List<Tool>,
    private val systemPrompt: () -> String,
    private val speak: suspend (String) -> Unit,
    private val notify: (String) -> Unit,
    /** chamada do intercomunicador em andamento (o microfone é dela) */
    private val inCall: () -> Boolean = { false },
    /** antes de ouvir: pausa o microfone da chamada e liga o microfone do capacete */
    private val beforeListen: suspend () -> Unit = {},
    private val afterListen: () -> Unit = {},
) {
    private val prefs = ctx.getSharedPreferences("preferencias", Context.MODE_PRIVATE)
    private val brain = Brain(tools)
    private val input = SpeechInput(ctx)
    private val wake = WakeWord(ctx) { scope.launch { converse(fromWake = true) } }

    var status by mutableStateOf(JarvisStatus.OFF); private set
    var wakeOn by mutableStateOf(prefs.getBoolean("jarvis_escuta", true)); private set
    var prepareProgress by mutableStateOf(0); private set
    /** o que o piloto falou por último */
    var heard by mutableStateOf(""); private set
    /** a última resposta do Jarvis (mostrada no balão) */
    var reply by mutableStateOf(""); private set
    /** muda a cada interação, para o balão saber quando sumir */
    var replyVersion by mutableStateOf(0); private set

    private var foreground = true
    private var busy: Job? = null
    private var prepareJob: Job? = null

    val busyNow: Boolean get() = status == JarvisStatus.LISTENING || status == JarvisStatus.THINKING || status == JarvisStatus.SPEAKING

    fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Liga/desliga a escuta contínua da palavra "Jarvis". */
    fun setWake(on: Boolean) {
        wakeOn = on
        prefs.edit().putBoolean("jarvis_escuta", on).apply()
        if (on) startWake() else {
            prepareJob?.cancel()
            wake.stop()
            if (!busyNow) status = JarvisStatus.OFF
        }
    }

    /** App visível ou não (o microfone só fica ligado com o app na tela). */
    fun setForeground(fg: Boolean) {
        foreground = fg
        if (fg) {
            if (wakeOn && !busyNow) startWake()
        } else {
            wake.stop()
            if (!busyNow && status == JarvisStatus.WAITING) status = JarvisStatus.OFF
        }
    }

    private fun startWake() {
        if (!wakeOn || !foreground || !hasMic() || inCall()) return
        if (prepareJob?.isActive == true) return
        prepareJob = scope.launch {
            try {
                if (!wake.modelReady()) {
                    status = JarvisStatus.PREPARING
                    notify("Baixando o reconhecedor do \"Jarvis\" (40 MB, só desta vez)…")
                    wake.ensureModel { p -> scope.launch { prepareProgress = p } }
                }
                wake.load()
                if (!wakeOn || !foreground || busyNow || inCall()) return@launch
                if (wake.start()) status = JarvisStatus.WAITING
                else {
                    status = JarvisStatus.OFF
                    notify("Não consegui ligar o microfone para o Jarvis.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MotoRota", "Jarvis: preparo falhou: $e")
                status = JarvisStatus.OFF
                notify("Não deu para baixar o reconhecedor do Jarvis. Tente com internet.")
            }
        }
    }

    /** Botão do microfone: fala com o Jarvis agora (ou cancela se ele já estiver ouvindo). */
    fun trigger() {
        if (busyNow) {
            cancel(); return
        }
        scope.launch { converse(fromWake = false) }
    }

    fun cancel() {
        busy?.cancel()
        busy = null
        status = JarvisStatus.OFF
        resumeWake()
    }

    /** Relatório do botão "Testar o Jarvis" nos Ajustes. */
    suspend fun diagnose(): String = brain.diagnose(Keys.gemini)

    fun dismissReply() {
        reply = ""
        heard = ""
        replyVersion++
    }

    private fun resumeWake() {
        if (wakeOn && foreground) startWake() else if (!busyNow) status = JarvisStatus.OFF
    }

    private fun converse(fromWake: Boolean) {
        if (busy?.isActive == true) return
        busy = scope.launch {
            wake.stop()
            try {
                val key = Keys.gemini
                if (key.isBlank()) {
                    reply = "Falta a chave do Gemini. Coloque em Ajustes, Assistente Jarvis."
                    replyVersion++
                    status = JarvisStatus.SPEAKING
                    speak(reply)
                    return@launch
                }
                if (!hasMic() || !input.available()) {
                    notify("Sem permissão de microfone ou sem reconhecimento de voz do Google no celular.")
                    return@launch
                }
                // Conversa contínua: depois de cada resposta ele continua ouvindo, sem precisar
                // chamar "Jarvis" de novo. Se o piloto ficar quieto, ele sai sozinho.
                var rounds = 0
                var retried = false
                while (rounds < 12) {
                    rounds++
                    status = JarvisStatus.LISTENING
                    heard = ""
                    if (rounds == 1) {
                        reply = ""
                        replyVersion++
                    }
                    beep(followUp = rounds > 1)
                    delay(180)
                    beforeListen()
                    val text = try {
                        input.listen { partial -> heard = partial }?.trim().orEmpty()
                    } finally {
                        afterListen()
                    }
                    if (text.isBlank()) {
                        // na primeira vez pede para repetir; depois, silêncio = fim da conversa
                        if (rounds == 1 && fromWake && !retried) {
                            retried = true
                            rounds = 0
                            status = JarvisStatus.SPEAKING
                            speak("Não ouvi. Pode repetir?")
                            continue
                        }
                        endTone()
                        break
                    }
                    heard = text
                    if (isGoodbye(text)) {
                        reply = "Beleza, tô aqui se precisar."
                        replyVersion++
                        status = JarvisStatus.SPEAKING
                        speak(reply)
                        break
                    }
                    status = JarvisStatus.THINKING
                    val answer = try {
                        // roda à parte: se o Google travar, o Jarvis desiste em vez de ficar "pensando" para sempre
                        val prompt = systemPrompt()
                        val job = scope.async(kotlinx.coroutines.Dispatchers.IO) { brain.ask(key, prompt, text) }
                        kotlinx.coroutines.withTimeoutOrNull(55_000) { job.await() } ?: run {
                            job.cancel()
                            "O Gemini demorou demais para responder. Teste em Ajustes, Testar o Jarvis."
                        }
                    } catch (e: BrainException) {
                        e.message ?: "Deu erro."
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "Deu um erro inesperado: ${e.message ?: e.javaClass.simpleName}."
                    }
                    reply = clean(answer)
                    replyVersion++
                    status = JarvisStatus.SPEAKING
                    speak(reply)
                }
            } finally {
                status = JarvisStatus.OFF
                busy = null
                resumeWake()
            }
        }
    }

    /** Chamada do intercomunicador começou: o microfone passa a ser da chamada. */
    fun pauseForCall() {
        prepareJob?.cancel()
        wake.stop()
        if (status == JarvisStatus.WAITING) status = JarvisStatus.OFF
    }

    /** Chamada acabou: volta a esperar o "Jarvis". */
    fun resumeAfterCall() {
        if (!busyNow) resumeWake()
    }

    /** Detector do "Jarvis" que escuta pelo microfone da chamada (null se o modelo não estiver pronto). */
    fun feederForCall(): WakeWord.Feeder? = wake.feeder()

    /**
     * Faz uma pergunta em voz alta e ouve a resposta: true = sim, false = não,
     * null = não entendeu / não deu para ouvir (o chamado continua tocando na tela).
     */
    suspend fun askYesNo(question: String): Boolean? {
        if (busyNow) return null
        wake.stop()
        try {
            reply = question
            replyVersion++
            status = JarvisStatus.SPEAKING
            speak(question)
            if (!hasMic() || !input.available()) return null
            repeat(2) { attempt ->
                status = JarvisStatus.LISTENING
                heard = ""
                beep()
                delay(180)
                beforeListen()
                val t = try {
                    input.listen { partial -> heard = partial }
                } finally {
                    afterListen()
                }
                val ans = t?.let { yesNo(it) }
                if (t != null) heard = t
                if (ans != null) return ans
                if (attempt == 0) {
                    status = JarvisStatus.SPEAKING
                    speak("Atendo? Sim ou não?")
                }
            }
            return null
        } finally {
            status = JarvisStatus.OFF
            resumeWake()
        }
    }

    private fun yesNo(t: String): Boolean? {
        val s = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        val words = s.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        val no = listOf("nao", "recusa", "recuse", "depois", "ocupado", "negativo", "deixa")
        val yes = listOf("sim", "pode", "atende", "atenda", "atender", "manda", "bora", "claro", "ok", "beleza", "fala", "quero", "positivo", "liga")
        return when {
            words.any { it in no } -> false
            words.any { it in yes } -> true
            else -> null
        }
    }

    private fun isGoodbye(t: String): Boolean {
        val s = t.lowercase()
        return s == "nada" || s.startsWith("nada não") || s.startsWith("deixa") || s.startsWith("cancela") ||
            s.startsWith("esquece") || s == "obrigado" || s == "valeu" || s == "obrigado jarvis" ||
            s == "valeu jarvis" || s.startsWith("tchau") || s == "só isso" || s == "é só isso"
    }

    /** Tira marcações que ficariam estranhas faladas (asteriscos, listas...). */
    private fun clean(s: String) = s.replace(Regex("[*#_`]"), "").replace(Regex("\\s+"), " ").trim()

    /** bip de "pode falar" (mais curto e baixo quando é continuação da conversa) */
    private fun beep(followUp: Boolean = false) {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, if (followUp) 45 else 70)
            tg.startTone(if (followUp) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_BEEP2, if (followUp) 90 else 160)
            scope.launch { delay(400); tg.release() }
        }
    }

    /** som de "encerrei a conversa" */
    private fun endTone() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 40)
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            scope.launch { delay(400); tg.release() }
        }
    }

    fun release() {
        busy?.cancel()
        prepareJob?.cancel()
        wake.release()
    }
}

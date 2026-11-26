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
        if (!wakeOn || !foreground || !hasMic()) return
        if (prepareJob?.isActive == true) return
        prepareJob = scope.launch {
            try {
                if (!wake.modelReady()) {
                    status = JarvisStatus.PREPARING
                    notify("Baixando o reconhecedor do \"Jarvis\" (40 MB, só desta vez)…")
                    wake.ensureModel { p -> scope.launch { prepareProgress = p } }
                }
                wake.load()
                if (!wakeOn || !foreground || busyNow) return@launch
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
                var again = true
                var rounds = 0
                while (again && rounds < 3) {
                    rounds++
                    again = false
                    status = JarvisStatus.LISTENING
                    heard = ""
                    reply = ""
                    replyVersion++
                    beep()
                    delay(180)
                    val text = input.listen { partial -> heard = partial }?.trim().orEmpty()
                    if (text.isBlank()) {
                        if (!fromWake || rounds > 1) break
                        status = JarvisStatus.SPEAKING
                        speak("Não ouvi. Pode repetir?")
                        again = true
                        continue
                    }
                    heard = text
                    if (isGoodbye(text)) {
                        reply = "Beleza, tô aqui se precisar."
                        status = JarvisStatus.SPEAKING
                        speak(reply)
                        break
                    }
                    status = JarvisStatus.THINKING
                    val answer = try {
                        brain.ask(key, systemPrompt(), text)
                    } catch (e: BrainException) {
                        e.message ?: "Deu erro."
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "Estou sem internet agora."
                    }
                    reply = clean(answer)
                    replyVersion++
                    status = JarvisStatus.SPEAKING
                    speak(reply)
                    // se o Jarvis fez uma pergunta, já ouve a resposta sem precisar chamar de novo
                    again = reply.trimEnd().endsWith("?")
                }
            } finally {
                status = JarvisStatus.OFF
                busy = null
                resumeWake()
            }
        }
    }

    private fun isGoodbye(t: String): Boolean {
        val s = t.lowercase()
        return s == "nada" || s.startsWith("nada não") || s.startsWith("deixa") || s.startsWith("cancela") ||
            s.startsWith("esquece") || s == "obrigado" || s == "valeu"
    }

    /** Tira marcações que ficariam estranhas faladas (asteriscos, listas...). */
    private fun clean(s: String) = s.replace(Regex("[*#_`]"), "").replace(Regex("\\s+"), " ").trim()

    private fun beep() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 160)
            scope.launch { delay(400); tg.release() }
        }
    }

    fun release() {
        busy?.cancel()
        prepareJob?.cancel()
        wake.release()
    }
}

package com.motorota.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Fica ouvindo só a palavra "Jarvis", 100% no celular (sem internet e sem mandar áudio
 * para ninguém), usando o reconhecedor Vosk com vocabulário travado em uma palavra.
 *
 * O modelo de voz (~40 MB) é baixado uma única vez na primeira vez que o recurso é ligado.
 */
class WakeWord(private val ctx: Context, private val onWake: () -> Unit) {
    private var model: Model? = null
    private var service: SpeechService? = null
    private var fired = false

    private val root get() = File(ctx.filesDir, "vosk")
    private val modelDir get() = File(root, MODEL_NAME)

    val listening: Boolean get() = service != null

    fun modelReady(): Boolean = File(modelDir, "am/final.mdl").exists() && File(modelDir, "conf/mfcc.conf").exists()

    /** Baixa e descompacta o modelo, informando o progresso (0-100). */
    suspend fun ensureModel(progress: (Int) -> Unit): Unit = withContext(Dispatchers.IO) {
        if (modelReady()) return@withContext
        root.mkdirs()
        val zip = File(root, "modelo.zip.part")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 40_000_000L
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = (done * 90 / total).toInt().coerceIn(0, 90)
                        if (pct != lastPct) { lastPct = pct; progress(pct) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val canonicalRoot = root.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val f = File(root, e.name)
                if (!f.canonicalPath.startsWith(canonicalRoot)) continue // proteção contra caminhos maliciosos
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zin.copyTo(it) }
                }
            }
        }
        zip.delete()
        progress(100)
        if (!modelReady()) throw Exception("modelo de voz incompleto")
        Unit
    }

    /** Carrega o modelo (lento, ~1 s) fora da tela principal. */
    suspend fun load(): Unit = withContext(Dispatchers.IO) {
        if (model == null) model = Model(modelDir.absolutePath)
    }

    /** Começa a ouvir. Chamar na thread principal, depois de load(). */
    fun start(): Boolean {
        if (service != null) return true
        val m = model ?: return false
        return try {
            fired = false
            val rec = Recognizer(m, 16000f, GRAMMAR).apply { setWords(true) }
            val s = SpeechService(rec, 16000f)
            s.startListening(listener)
            service = s
            true
        } catch (e: Exception) {
            Log.w("MotoRota", "Jarvis: microfone indisponível: $e")
            false
        }
    }

    /** Para de ouvir e libera o microfone. */
    fun stop() {
        val s = service ?: return
        service = null
        runCatching { s.stop() }
        runCatching { s.shutdown() }
    }

    /**
     * Detector alimentado com áudio de fora (16 kHz). Usado durante a chamada do intercomunicador,
     * quando o microfone já está aberto pela chamada.
     */
    inner class Feeder internal constructor(private val rec: Recognizer) {
        private var lastHit = 0L

        fun feed(buf: ShortArray, n: Int) {
            if (!rec.acceptWaveForm(buf, n)) return
            if (!confident(rec.result)) return
            rec.reset()
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastHit < 4000) return
            lastHit = now
            android.os.Handler(android.os.Looper.getMainLooper()).post { onWake() }
        }

        fun close() {
            runCatching { rec.close() }
        }
    }

    /** null se o modelo ainda não foi carregado. */
    fun feeder(): Feeder? {
        val m = model ?: return null
        return try {
            Feeder(Recognizer(m, 16000f, GRAMMAR).apply { setWords(true) })
        } catch (e: Exception) {
            null
        }
    }

    fun release() {
        stop()
        runCatching { model?.close() }
        model = null
    }

    /**
     * Só aceita "jarvis" no resultado final da frase, com confiança alta e duração de palavra
     * falada de verdade. Ruído, conversa e palavras parecidas caem nas palavras "isca" da gramática.
     */
    private fun confident(json: String?): Boolean {
        val o = runCatching { JSONObject(json ?: return false) }.getOrNull() ?: return false
        val words = o.optJSONArray("result") ?: return false
        var hit = false
        for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            if (w.optString("word") != "jarvis") continue
            val conf = w.optDouble("conf", 0.0)
            val dur = w.optDouble("end", 0.0) - w.optDouble("start", 0.0)
            Log.d("MotoRota", "Jarvis: ouviu jarvis conf=$conf dur=$dur (${o.optString("text")})")
            if (conf >= MIN_CONF && dur in 0.28..1.4) hit = true
        }
        // frase longa com "jarvis" no meio de muita palavra = conversa, não chamado
        val total = o.optString("text").split(' ').count { it.isNotBlank() }
        return hit && total <= 4
    }

    private fun fire() {
        if (fired) return
        fired = true
        stop()
        onWake()
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}

        override fun onResult(hypothesis: String?) {
            if (confident(hypothesis)) fire()
        }

        override fun onFinalResult(hypothesis: String?) {
            if (confident(hypothesis)) fire()
        }

        override fun onError(exception: Exception?) {
            Log.w("MotoRota", "Jarvis: erro no reconhecedor: $exception")
            stop()
        }

        override fun onTimeout() {
            stop()
        }
    }

    companion object {
        private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"

        private const val MIN_CONF = 0.93

        /**
         * "jarvis" + palavras "isca" de som parecido: com só uma palavra na gramática, o reconhecedor
         * achava "jarvis" em quase qualquer barulho. As iscas absorvem esses sons e são ignoradas.
         */
        private val GRAMMAR = org.json.JSONArray(
            listOf(
                "jarvis", "hey jarvis", "hi jarvis", "ok jarvis",
                "service", "nervous", "harvest", "travis", "jars", "jar", "this", "is", "was", "yes", "us",
                "jazz", "chavis", "davis", "mavis", "garbage", "darkness", "office", "starve", "service is",
                "yeah", "no", "hey", "hi", "okay", "the", "a", "and", "so", "you", "what", "very", "she", "says",
                "[unk]",
            ),
        ).toString()
    }
}

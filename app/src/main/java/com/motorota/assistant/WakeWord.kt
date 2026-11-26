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
            val rec = Recognizer(m, 16000f, GRAMMAR)
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
            val final = rec.acceptWaveForm(buf, n)
            val hit = if (final) heard(rec.result, "text") else heard(rec.partialResult, "partial")
            if (!hit) return
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
            Feeder(Recognizer(m, 16000f, GRAMMAR))
        } catch (e: Exception) {
            null
        }
    }

    fun release() {
        stop()
        runCatching { model?.close() }
        model = null
    }

    private fun heard(json: String?, key: String): Boolean {
        val text = runCatching { JSONObject(json ?: return false).optString(key) }.getOrNull() ?: return false
        return text.split(' ').any { it == "jarvis" }
    }

    private fun fire() {
        if (fired) return
        fired = true
        stop()
        onWake()
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            if (heard(hypothesis, "partial")) fire()
        }

        override fun onResult(hypothesis: String?) {
            if (heard(hypothesis, "text")) fire()
        }

        override fun onFinalResult(hypothesis: String?) {
            if (heard(hypothesis, "text")) fire()
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

        /** Só reconhece "jarvis"; qualquer outro som cai em [unk] e é ignorado. */
        private const val GRAMMAR = "[\"jarvis\", \"hey jarvis\", \"[unk]\"]"
    }
}

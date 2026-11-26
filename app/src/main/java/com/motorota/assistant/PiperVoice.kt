package com.motorota.assistant

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Voz neural do Jarvis que roda DENTRO do celular (Piper, via sherpa-onnx):
 * grátis, sem limite, sem internet depois de baixada (~65 MB, uma vez só).
 */
object PiperVoice {
    /** id -> nome mostrado */
    val VOICES = listOf(
        "faber" to "Faber (grave, calma)",
        "cadu" to "Cadu (jovem)",
        "jeff" to "Jeff (firme)",
    )

    private var loadedId: String? = null
    private var tts: OfflineTts? = null

    @Volatile var downloading: String? = null; private set
    @Volatile var progress = 0; private set

    private fun dirName(id: String) = "vits-piper-pt_BR-$id-medium"
    private fun root(ctx: Context) = File(ctx.filesDir, "vozes")
    private fun modelDir(ctx: Context, id: String) = File(root(ctx), dirName(id))

    fun ready(ctx: Context, id: String): Boolean {
        val d = modelDir(ctx, id)
        return File(d, "pt_BR-$id-medium.onnx").exists() && File(d, "tokens.txt").exists() &&
            File(d, "espeak-ng-data/phontab").exists()
    }

    /** Baixa e descompacta a voz (só na primeira vez). */
    suspend fun download(ctx: Context, id: String) = withContext(Dispatchers.IO) {
        if (ready(ctx, id)) return@withContext
        downloading = id
        progress = 0
        val dest = root(ctx).apply { mkdirs() }
        val tmp = File(dest, "$id.tar.bz2.part")
        try {
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${dirName(id)}.tar.bz2"
            var conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            // o GitHub redireciona para outro domínio: segue manualmente se precisar
            var hops = 0
            while (conn.responseCode in 300..399 && hops < 5) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(next).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                hops++
            }
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 67_000_000L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress = (done * 90 / total).toInt().coerceIn(0, 90)
                    }
                }
            }
            conn.disconnect()
            val canonical = dest.canonicalPath + File.separator
            TarArchiveInputStream(BZip2CompressorInputStream(tmp.inputStream().buffered())).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: break
                    val f = File(dest, e.name)
                    if (!f.canonicalPath.startsWith(canonical)) continue
                    if (e.isDirectory) f.mkdirs() else {
                        f.parentFile?.mkdirs()
                        f.outputStream().use { tar.copyTo(it) }
                    }
                }
            }
            progress = 100
            if (!ready(ctx, id)) throw Exception("voz incompleta")
        } finally {
            tmp.delete()
            downloading = null
        }
    }

    @Synchronized
    private fun engine(ctx: Context, id: String): OfflineTts {
        tts?.let { if (loadedId == id) return it }
        tts?.release()
        tts = null
        val d = modelDir(ctx, id)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(d, "pt_BR-$id-medium.onnx").absolutePath,
                    tokens = File(d, "tokens.txt").absolutePath,
                    dataDir = File(d, "espeak-ng-data").absolutePath,
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        )
        return OfflineTts(config = config).also {
            tts = it
            loadedId = id
        }
    }

    /** Gera a fala (PCM 16 bits). null se a voz ainda não foi baixada ou deu erro. */
    suspend fun synth(ctx: Context, id: String, text: String, speed: Float = 1.05f): JarvisVoice.Audio? =
        withContext(Dispatchers.Default) {
            if (!ready(ctx, id) || text.isBlank()) return@withContext null
            try {
                val audio = engine(ctx, id).generate(text, 0, speed)
                val s = audio.samples
                val pcm = ByteArray(s.size * 2)
                for (i in s.indices) {
                    val v = (s[i] * 32767f).toInt().coerceIn(-32768, 32767)
                    pcm[2 * i] = (v and 0xff).toByte()
                    pcm[2 * i + 1] = ((v shr 8) and 0xff).toByte()
                }
                JarvisVoice.Audio(pcm, audio.sampleRate)
            } catch (e: Throwable) {
                Log.w("MotoRota", "Piper: $e")
                null
            }
        }

    /** Carrega a voz na memória antes de precisar (a primeira fala fica rápida). */
    suspend fun warmUp(ctx: Context, id: String) = withContext(Dispatchers.Default) {
        if (ready(ctx, id)) runCatching { engine(ctx, id) }
    }
}

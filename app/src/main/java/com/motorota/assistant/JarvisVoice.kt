package com.motorota.assistant

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import com.motorota.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Voz natural do Jarvis gerada pelo Google (Gemini TTS), no lugar da voz robótica do celular.
 * Usa a mesma chave do Gemini. Se falhar (sem internet, limite), quem fala é a voz do celular.
 */
object JarvisVoice {
    /** vozes do Google com cara de "Jarvis": graves e calmas */
    val VOICES = listOf(
        "Charon" to "Charon (grave e calma)",
        "Orus" to "Orus (firme)",
        "Iapetus" to "Iapetus (clara)",
        "Algenib" to "Algenib (rouca)",
    )

    // o "lite" gera mais rápido e tem cota própria; se um esgotar, usa o outro (mesma voz)
    private val MODELS = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts", "gemini-2.5-flash-preview-tts")
    private var workingModel: String? = null
    /** modelos que estouraram a cota grátis, e até quando evitar */
    private val exhausted = HashMap<String, Long>()

    /** frases já geradas (as repetidas, como "Não ouvi", saem na hora e não gastam cota) */
    private val cache = object : LinkedHashMap<String, Audio>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Audio>?) = size > 40
    }

    /** a última falha foi por limite grátis (para avisar o piloto uma vez) */
    @Volatile var quotaHit = false; private set

    /** Áudio PCM 16 bits mono + taxa de amostragem, ou null se não deu. */
    class Audio(val pcm: ByteArray, val rate: Int)

    suspend fun synth(key: String, text: String, voice: String): Audio? = withContext(Dispatchers.IO) {
        if (key.isBlank() || text.isBlank()) return@withContext null
        val cacheKey = "$voice|$text"
        synchronized(cache) { cache[cacheKey] }?.let { return@withContext it }
        val now = System.currentTimeMillis()
        val models = (listOfNotNull(workingModel) + MODELS.filter { it != workingModel })
            .filter { (exhausted[it] ?: 0L) < now }
        quotaHit = false
        for (model in models) {
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put(
                            "speechConfig",
                            JSONObject()
                                .put("languageCode", "pt-BR")
                                .put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))),
                        ),
                )
            val r = Http.request(
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", "POST",
                mapOf("x-goog-api-key" to key), body.toString(), readTimeoutMs = 12_000,
            )
            if (r.code == 404 || r.code == 400) continue // modelo indisponível para esta chave: tenta o próximo
            if (r.code == 429 || r.code == 503) {
                // cota grátis deste modelo acabou (ou está sobrecarregado): tenta o próximo, com a mesma voz
                exhausted[model] = now + if (r.code == 429) 30 * 60_000L else 60_000L
                if (r.code == 429) quotaHit = true
                Http.report("jarvis-voz", "$model -> ${r.code} ${r.reason()}")
                continue
            }
            if (!r.ok) {
                Http.report("jarvis-voz", "$model -> ${r.code} ${r.reason()} | ${r.text.take(300)}")
                return@withContext null
            }
            val part = JSONObject(r.text).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optJSONObject("inlineData")
                ?: return@withContext null
            val bytes = Base64.decode(part.optString("data"), Base64.DEFAULT)
            val mime = part.optString("mimeType")
            workingModel = model
            quotaHit = false
            val audio = decode(bytes, mime)
            if (audio != null && text.length < 160) synchronized(cache) { cache[cacheKey] = audio }
            return@withContext audio
        }
        null
    }

    /** O modelo novo devolve WAV; os antigos, PCM cru ("audio/L16;rate=24000"). */
    private fun decode(bytes: ByteArray, mime: String): Audio? {
        if (bytes.size > 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val rate = bb.getInt(24)
            // procura o bloco "data"
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val len = bb.getInt(pos + 4)
                if (id == "data") {
                    val end = minOf(bytes.size, pos + 8 + len)
                    return Audio(bytes.copyOfRange(pos + 8, end), rate)
                }
                pos += 8 + len + (len and 1)
            }
            return null
        }
        val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
        return Audio(bytes, rate)
    }

    /** Toca e espera terminar. voiceCall = durante chamada do intercomunicador (sai pelo canal da ligação). */
    /** rateFactor < 1 deixa a voz mais grave e um pouco mais lenta */
    @Volatile private var current: AudioTrack? = null

    /** Corta a fala que estiver tocando (nunca duas vozes ao mesmo tempo). */
    fun stopAll() {
        current?.let { runCatching { it.pause(); it.flush() } }
        current = null
    }

    suspend fun play(a: Audio, voiceCall: Boolean, rateFactor: Float = 1f) {
        val rate = (a.rate * rateFactor).toInt()
        val track = withContext(Dispatchers.IO) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (voiceCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
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
                .setBufferSizeInBytes(a.pcm.size.coerceAtLeast(4096))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .also { it.write(a.pcm, 0, a.pcm.size) }
        }
        try {
            stopAll()
            current = track
            track.play()
            val end = android.os.SystemClock.elapsedRealtime() + a.pcm.size / 2 * 1000L / rate + 150
            while (android.os.SystemClock.elapsedRealtime() < end && current === track) delay(80)
        } finally {
            if (current === track) current = null
            runCatching { track.stop() }
            track.release()
        }
    }
}

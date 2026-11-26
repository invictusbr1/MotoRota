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

    private val MODELS = listOf("gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts", "gemini-2.5-flash-preview-tts")
    private var workingModel: String? = null

    /** Áudio PCM 16 bits mono + taxa de amostragem, ou null se não deu. */
    class Audio(val pcm: ByteArray, val rate: Int)

    suspend fun synth(key: String, text: String, voice: String): Audio? = withContext(Dispatchers.IO) {
        if (key.isBlank() || text.isBlank()) return@withContext null
        val models = listOfNotNull(workingModel) + MODELS.filter { it != workingModel }
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
            return@withContext decode(bytes, mime)
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
    suspend fun play(a: Audio, voiceCall: Boolean) {
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
                        .setSampleRate(a.rate)
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
            track.play()
            val ms = a.pcm.size / 2 * 1000L / a.rate
            delay(ms + 150)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}

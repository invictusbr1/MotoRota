package com.motorota.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Uma ação do app que a IA pode chamar (ex.: traçar rota, contar radares). */
class Tool(
    val name: String,
    val description: String,
    /** nome -> (tipo STRING/NUMBER/BOOLEAN, descrição) */
    val params: Map<String, Pair<String, String>> = emptyMap(),
    val required: List<String> = emptyList(),
    val run: suspend (JSONObject) -> String,
)

class BrainException(message: String) : Exception(message)

/**
 * "Cérebro" do Jarvis: conversa com o Google Gemini, que decide sozinho quando
 * chamar as ações do app (function calling) e monta a resposta falada.
 */
class Brain(private val tools: List<Tool>) {
    private val history = ArrayList<JSONObject>()
    private var workingModel: String? = null
    private var useThinkingOff = true

    fun reset() = history.clear()

    suspend fun ask(key: String, systemPrompt: String, userText: String): String = withContext(Dispatchers.IO) {
        val turn = ArrayList<JSONObject>()
        turn.add(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText))))

        repeat(5) {
            val resp = call(key, systemPrompt, history + turn)
            val content = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?: throw BrainException(blockReason(resp))
            val parts = content.optJSONArray("parts") ?: JSONArray()
            val calls = (0 until parts.length()).mapNotNull { parts.getJSONObject(it).optJSONObject("functionCall") }
            if (calls.isEmpty()) {
                val text = (0 until parts.length())
                    .mapNotNull { i -> parts.getJSONObject(i).takeIf { !it.optBoolean("thought") }?.optString("text") }
                    .joinToString(" ").trim()
                remember(userText, text)
                return@withContext text.ifBlank { "Pronto." }
            }
            // a resposta do modelo volta inteira (inclui as "assinaturas" que o Gemini exige)
            if (!content.has("role")) content.put("role", "model")
            turn.add(content)
            val answers = JSONArray()
            for (c in calls) {
                val name = c.optString("name")
                val args = c.optJSONObject("args") ?: JSONObject()
                val tool = tools.firstOrNull { it.name == name }
                val result = try {
                    tool?.run?.invoke(args) ?: "Função desconhecida."
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Erro ao executar: ${e.message ?: e.javaClass.simpleName}"
                }
                answers.put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject().put("name", name).put("response", JSONObject().put("resultado", result)),
                    ),
                )
            }
            turn.add(JSONObject().put("role", "user").put("parts", answers))
        }
        "Não consegui terminar isso agora."
    }

    /** Guarda só o texto da conversa (últimas trocas) para o Jarvis lembrar do contexto. */
    private fun remember(user: String, reply: String) {
        history.add(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user))))
        history.add(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", reply.ifBlank { "ok" }))))
        while (history.size > 16) history.removeAt(0)
    }

    private fun blockReason(resp: JSONObject): String {
        val r = resp.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        return if (r.isNotBlank()) "A IA recusou responder ($r)." else "A IA não respondeu."
    }

    private fun declarations(): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            val d = JSONObject().put("name", t.name).put("description", t.description)
            if (t.params.isNotEmpty()) {
                val props = JSONObject()
                t.params.forEach { (k, v) -> props.put(k, JSONObject().put("type", v.first).put("description", v.second)) }
                val p = JSONObject().put("type", "OBJECT").put("properties", props)
                if (t.required.isNotEmpty()) p.put("required", JSONArray(t.required))
                d.put("parameters", p)
            }
            arr.put(d)
        }
        return arr
    }

    private fun call(key: String, systemPrompt: String, contents: List<JSONObject>): JSONObject {
        val models = listOfNotNull(workingModel) + MODELS.filter { it != workingModel }
        var lastErr = "sem resposta"
        for (model in models) {
            for (attempt in 0..1) {
                val gen = JSONObject().put("temperature", 0.6).put("maxOutputTokens", 600)
                if (useThinkingOff && attempt == 0) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                    .put("contents", JSONArray(contents))
                    .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations())))
                    .put("generationConfig", gen)
                val (code, text) = post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", key, body.toString())
                when {
                    code in 200..299 -> { workingModel = model; return JSONObject(text) }
                    code == 400 && text.contains("API key", ignoreCase = true) ->
                        throw BrainException("A chave do Gemini não é válida. Confira nos Ajustes.")
                    code == 403 -> throw BrainException("A chave do Gemini não tem permissão. Gere outra no AI Studio.")
                    code == 429 -> throw BrainException("Limite gratuito do Gemini atingido. Tente daqui a pouco.")
                    code == 400 && attempt == 0 && text.contains("thinking", ignoreCase = true) -> {
                        useThinkingOff = false; lastErr = "HTTP 400"; continue
                    }
                    code == 404 || code == 400 -> { lastErr = "HTTP $code"; break } // tenta o próximo modelo
                    else -> throw BrainException("Erro no Gemini (HTTP $code).")
                }
            }
        }
        throw BrainException("Não consegui falar com o Gemini ($lastErr).")
    }

    private fun post(url: String, key: String, body: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("x-goog-api-key", key)
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val MODELS = listOf("gemini-2.5-flash", "gemini-flash-latest", "gemini-2.0-flash")
    }
}

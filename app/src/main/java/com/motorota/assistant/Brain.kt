package com.motorota.assistant

import android.util.Log
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
    /** null = decide pelo formato da chave; true/false = já descobriu qual serviço funciona */
    private var useVertex: Boolean? = null

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
            val gen = JSONObject().put("temperature", 0.6).put("maxOutputTokens", 800)
            // só a família 2.5 aceita desligar o "raciocínio" assim; as novas ficam no padrão
            if (model.startsWith("gemini-2.5")) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                .put("contents", JSONArray(contents))
                .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations())))
                .put("generationConfig", gen)
                .toString()

            // Chave "AIza..." = Gemini do AI Studio; chave "AQ...." = Vertex AI (modo express) do Google Cloud
            val first = useVertex ?: key.startsWith("AQ.")
            var (code, text) = post(endpoint(first, model, key), if (first) null else key, body)
            if ((code == 401 || code == 403) && useVertex == null) {
                // tenta o outro serviço do Google com a mesma chave
                val (code2, text2) = post(endpoint(!first, model, key), if (!first) null else key, body)
                if (code2 != 401 && code2 != 403) {
                    useVertex = !first
                    code = code2
                    text = text2
                } else {
                    Log.w("MotoRota", "Gemini $code/$code2: ${text.take(300)} | ${text2.take(300)}")
                }
            } else if (code in 200..299 || code == 404) {
                if (useVertex == null && code in 200..299) useVertex = first
            }
            when {
                code in 200..299 -> {
                    workingModel = model
                    return JSONObject(text)
                }
                code == 404 || (code == 400 && text.contains("model", ignoreCase = true) && !text.contains("API key", ignoreCase = true)) -> {
                    lastErr = "modelo $model indisponível"
                    continue // tenta o próximo modelo
                }
                code == 400 && text.contains("API key", ignoreCase = true) -> throw BrainException(friendly(code, text))
                code == 401 || code == 403 || code == 429 -> throw BrainException(friendly(code, text))
                else -> {
                    lastErr = "HTTP $code"
                    Log.w("MotoRota", "Gemini $model HTTP $code: ${text.take(300)}")
                }
            }
        }
        throw BrainException("Não consegui falar com o Gemini ($lastErr).")
    }

    private fun endpoint(vertex: Boolean, model: String, key: String): String =
        if (vertex) {
            "https://aiplatform.googleapis.com/v1/publishers/google/models/$model:generateContent?key=" +
                java.net.URLEncoder.encode(key, "UTF-8")
        } else {
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        }

    /** Mensagem curta (vai ser falada) explicando o erro do Google. */
    private fun friendly(code: Int, text: String): String {
        val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
        return when {
            code == 429 -> "Limite gratuito do Gemini atingido. Tente daqui a pouco."
            msg.contains("API key not valid", true) || msg.contains("API_KEY_INVALID", true) ->
                "A chave do Gemini não é válida. Confira o arquivo de chaves."
            msg.contains("has not been used", true) || msg.contains("disabled", true) ->
                "A API do Gemini não está ativada nessa conta do Google. Gere a chave em aistudio.google.com."
            msg.contains("billing", true) -> "Essa chave do Google pede cobrança ativada. Use uma chave grátis do AI Studio."
            else -> "O Google recusou a chave do Gemini (erro $code)."
        }
    }

    private fun post(url: String, key: String?, body: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (key != null) conn.setRequestProperty("x-goog-api-key", key)
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
        // do mais novo para o mais antigo; o primeiro que existir na conta fica memorizado
        private val MODELS = listOf(
            "gemini-3.8-flash", "gemini-flash-latest", "gemini-3.6-flash", "gemini-3.5-flash",
            "gemini-3.5-flash-lite", "gemini-flash-lite-latest", "gemini-2.5-flash",
        )
    }
}

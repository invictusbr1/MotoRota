package com.motorota.assistant

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
    /** null = ainda não sabe; true/false = já descobriu qual serviço do Google funciona com a chave */
    private var useVertex: Boolean? = null
    /** nível de raciocínio que cada modelo aceita (descoberto na prática e memorizado) */
    private val thinking = HashMap<String, Int>() // índice em THINK_LEVELS

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
            var attempt = 0
            while (attempt < 4) {
                attempt++
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                    .put("contents", JSONArray(contents))
                    .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations())))
                    .put("generationConfig", genConfig(model))
                    .toString()

                // AI Studio primeiro (serve para as chaves "AIza..." e para as novas "AQ...."); Vertex AI só se o AI Studio recusar
                val first = useVertex ?: false
                var r = post(endpoint(first, model, key), if (first) null else key, body)
                if ((r.code == 401 || r.code == 403) && useVertex == null) {
                    val r2 = post(endpoint(!first, model, key), if (!first) null else key, body)
                    if (r2.code != 401 && r2.code != 403) {
                        useVertex = !first
                        r = r2
                    }
                }
                when {
                    r.ok -> {
                        if (useVertex == null) useVertex = first
                        workingModel = model
                        return JSONObject(r.text)
                    }
                    // o modelo não aceitou esse nível de raciocínio: tenta o próximo nível (minimal -> low -> sem config)
                    r.code == 400 && r.text.contains("think", ignoreCase = true) && (thinking[model] ?: 0) < THINK_LEVELS.size -> {
                        thinking[model] = (thinking[model] ?: 0) + 1
                        attempt = 0
                        continue
                    }
                    // sobrecarregado / limite deste modelo: passa para o próximo modelo na hora
                    r.code == 503 || r.code == 429 && models.last() != model -> {
                        lastErr = r.reason()
                        break
                    }
                    r.code < 0 -> {
                        // demorou demais ou caiu: tenta um modelo mais leve antes de desistir
                        lastErr = r.reason()
                        break
                    }
                    r.code == 404 || (r.code == 400 && r.text.contains("model", ignoreCase = true) && !r.text.contains("API key", ignoreCase = true)) -> {
                        lastErr = "modelo $model indisponível"
                        break
                    }
                    r.code == 400 && r.text.contains("API key", ignoreCase = true) -> throw BrainException(friendly(r.code, r.text))
                    r.code == 401 || r.code == 403 || r.code == 429 -> throw BrainException(friendly(r.code, r.text))
                    else -> {
                        lastErr = "erro ${r.code}"
                        break
                    }
                }
            }
        }
        throw BrainException("Não consegui resposta do Google ($lastErr).")
    }

    /** Pede pouco "raciocínio" para o Jarvis responder rápido (é conversa de capacete, não prova de matemática). */
    private fun genConfig(model: String): JSONObject {
        val gen = JSONObject().put("temperature", 0.6).put("maxOutputTokens", 1024)
        if (model.startsWith("gemini-2.5")) {
            if ((thinking[model] ?: 0) == 0) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        } else {
            THINK_LEVELS.getOrNull(thinking[model] ?: 0)?.let {
                gen.put("thinkingConfig", JSONObject().put("thinkingLevel", it))
            }
        }
        return gen
    }

    /**
     * Teste rápido (botão nos Ajustes): tenta uma pergunta curtinha em cada serviço/modelo
     * e devolve um relatório do que o Google respondeu.
     */
    suspend fun diagnose(key: String): String = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext "Sem chave do Gemini."
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "Responda só: ok")))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 50).put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal")))
            .toString()
        val lines = ArrayList<String>()
        lines.add("Chave: ${key.take(4)}… (${key.length} letras)")
        for (vertex in listOf(false, true)) {
            val nome = if (vertex) "Vertex AI" else "AI Studio"
            for (model in MODELS.take(4)) {
                val t0 = System.currentTimeMillis()
                val (code, text) = try {
                    post(endpoint(vertex, model, key), if (vertex) null else key, body).let { it.code to (it.error ?: it.text) }
                } catch (e: Exception) {
                    -1 to (e.javaClass.simpleName + ": " + (e.message ?: ""))
                }
                val ms = System.currentTimeMillis() - t0
                val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: text.take(90)
                if (code in 200..299) {
                    useVertex = vertex
                    workingModel = model
                    lines.add("✓ $nome · $model funcionou (${ms / 1000.0}s)")
                    val ok = lines.joinToString("\n")
                    com.motorota.data.Http.report("jarvis-teste", ok)
                    return@withContext ok
                }
                lines.add("✗ $nome · $model: ${if (code < 0) "sem resposta" else "erro $code"} (${ms / 1000.0}s) ${msg.take(90)}")
                if (code == 401 || code == 403) break // chave não serve neste serviço: não adianta outro modelo
            }
        }
        val report = lines.joinToString("\n")
        com.motorota.data.Http.report("jarvis-teste", report)
        report
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

    private fun post(url: String, key: String?, body: String): com.motorota.data.HttpResult {
        val headers = if (key != null) mapOf("x-goog-api-key" to key) else emptyMap()
        val r = com.motorota.data.Http.request(url, "POST", headers, body, readTimeoutMs = 18_000)
        if (!r.ok) {
            com.motorota.data.Http.report(
                "jarvis",
                "${url.substringBefore('?')} -> ${r.code} ${r.reason()} | ${r.text.take(600)}",
            )
        }
        return r
    }

    companion object {
        private val THINK_LEVELS = listOf("minimal", "low")
        // do mais novo para o mais antigo; o primeiro que existir na conta fica memorizado
        private val MODELS = listOf(
            // o "lite" responde bem mais rápido e tem cota maior: ideal para conversa no capacete
            "gemini-3.5-flash-lite", "gemini-flash-lite-latest", "gemini-3.8-flash", "gemini-flash-latest",
            "gemini-3.6-flash", "gemini-3.5-flash", "gemini-2.5-flash",
        )
    }
}

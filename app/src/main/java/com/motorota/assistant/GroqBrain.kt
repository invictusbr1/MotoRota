package com.motorota.assistant

import com.motorota.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cérebro do Jarvis pelo Groq (formato OpenAI). Muito rápido e com plano grátis por dia.
 * Usa as mesmas ações (Tool) do Gemini.
 */
class GroqBrain(private val tools: List<Tool>) {
    private val history = ArrayList<JSONObject>()
    private var workingModel: String? = null
    /** modelos que não aceitam o parâmetro de raciocínio */
    private val noReasoningParam = HashSet<String>()

    fun reset() = history.clear()

    suspend fun ask(key: String, systemPrompt: String, userText: String): String = withContext(Dispatchers.IO) {
        val msgs = ArrayList<JSONObject>()
        msgs.add(JSONObject().put("role", "system").put("content", systemPrompt))
        msgs.addAll(history)
        msgs.add(JSONObject().put("role", "user").put("content", userText))

        repeat(6) {
            val resp = call(key, msgs)
            val msg = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw BrainException("O Groq não respondeu.")
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                val text = msg.optString("content").trim()
                remember(userText, text)
                return@withContext text.ifBlank { "Pronto." }
            }
            // devolve a mensagem do modelo (com as chamadas) e as respostas de cada ação
            val assistant = JSONObject().put("role", "assistant").put("tool_calls", calls)
            if (msg.has("content") && !msg.isNull("content")) assistant.put("content", msg.optString("content"))
            msgs.add(assistant)
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val fn = c.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }.getOrDefault(JSONObject())
                val tool = tools.firstOrNull { it.name == name }
                val result = try {
                    tool?.run?.invoke(args) ?: "Função desconhecida."
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Erro ao executar: ${e.message ?: e.javaClass.simpleName}"
                }
                msgs.add(JSONObject().put("role", "tool").put("tool_call_id", c.optString("id")).put("content", result))
            }
        }
        "Não consegui terminar isso agora."
    }

    private fun remember(user: String, reply: String) {
        // marca a hora: o modelo sabe que a resposta antiga pode estar velha (radares, distância...)
        history.add(JSONObject().put("role", "user").put("content", "[às ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())}] $user"))
        history.add(JSONObject().put("role", "assistant").put("content", reply.ifBlank { "ok" }))
        while (history.size > 16) history.removeAt(0)
    }

    private fun toolsJson(): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            val props = JSONObject()
            t.params.forEach { (k, v) -> props.put(k, JSONObject().put("type", v.first.lowercase()).put("description", v.second)) }
            val params = JSONObject().put("type", "object").put("properties", props)
            if (t.required.isNotEmpty()) params.put("required", JSONArray(t.required))
            arr.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", t.name).put("description", t.description).put("parameters", params),
                ),
            )
        }
        return arr
    }

    private fun call(key: String, msgs: List<JSONObject>): JSONObject {
        val models = listOfNotNull(workingModel) + MODELS.filter { it != workingModel }
        var lastErr = "sem resposta"
        for (model in models) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val body = JSONObject()
                    .put("model", model)
                    .put("messages", JSONArray(msgs))
                    .put("tools", toolsJson())
                    .put("tool_choice", "auto")
                    .put("temperature", 0.6)
                    .put("max_completion_tokens", 1024)
                if (model.startsWith("openai/gpt-oss") && model !in noReasoningParam) body.put("reasoning_effort", "low")
                val r = Http.request(
                    "https://api.groq.com/openai/v1/chat/completions", "POST",
                    mapOf("Authorization" to "Bearer $key"), body.toString(), readTimeoutMs = 15_000,
                )
                if (r.ok) {
                    workingModel = model
                    return JSONObject(r.text)
                }
                if (r.code > 0) Http.report("jarvis-groq", "$model -> ${r.code} ${r.reason()} | ${r.text.take(400)}")
                val msg = runCatching { JSONObject(r.text).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                when {
                    r.code == 400 && msg.contains("reasoning", true) && model !in noReasoningParam -> {
                        noReasoningParam.add(model); continue
                    }
                    r.code == 401 -> throw BrainException("A chave do Groq não é válida. Confira nos Ajustes.")
                    // modelo aposentado, sem acesso, limite ou sobrecarga: tenta o próximo modelo
                    else -> {
                        lastErr = if (r.code < 0) r.reason() else "erro ${r.code}"
                        break
                    }
                }
            }
        }
        throw BrainException("Groq indisponível ($lastErr).")
    }

    /** Teste rápido do botão "Testar o Jarvis". */
    suspend fun diagnose(key: String): String = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext "Groq: sem chave."
        for (model in MODELS) {
            val t0 = System.currentTimeMillis()
            val body = JSONObject().put("model", model)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Responda só: ok")))
                .put("max_completion_tokens", 20)
            val r = Http.request(
                "https://api.groq.com/openai/v1/chat/completions", "POST",
                mapOf("Authorization" to "Bearer $key"), body.toString(), readTimeoutMs = 15_000,
            )
            val s = (System.currentTimeMillis() - t0) / 1000.0
            if (r.ok) {
                workingModel = model
                return@withContext "✓ Groq · $model funcionou (${s}s)"
            }
            if (r.code == 401) return@withContext "✗ Groq: chave recusada (401)"
        }
        "✗ Groq: nenhum modelo respondeu"
    }

    companion object {
        private val MODELS = listOf(
            "openai/gpt-oss-120b", "qwen/qwen3.8-27b", "openai/gpt-oss-20b", "llama-3.3-70b-versatile",
        )
    }
}

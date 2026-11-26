package com.motorota.data

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Resultado de uma chamada HTTP: código (ou -1 se não chegou a ter resposta) e o texto. */
data class HttpResult(val code: Int, val text: String, val error: String? = null) {
    val ok: Boolean get() = code in 200..299

    /** explicação curta em português do que deu errado */
    fun reason(): String = when {
        error != null -> error
        code == 400 -> "pedido recusado (400)"
        code == 401 || code == 403 -> "chave recusada ($code)"
        code == 404 -> "não encontrado (404)"
        code == 429 -> "limite de uso atingido (429)"
        code >= 500 -> "servidor fora do ar ($code)"
        else -> "erro $code"
    }
}

/**
 * Chamadas HTTP sem esconder o motivo do erro.
 * Corrige uma pegadinha do Android: resposta 401 sem cabeçalho de desafio
 * vira IOException ("No authentication challenges found") em vez de código 401.
 */
object Http {
    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
    ): HttpResult {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return HttpResult(-1, "", "endereço inválido")
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", "MotoRota-Android/0.3 (github.com/invictusbr1/MotoRota)")
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                if (!headers.containsKey("Content-Type")) conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                if (e.message?.contains("authentication challenge", ignoreCase = true) == true) 401 else throw e
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = try {
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            } catch (_: IOException) {
                ""
            }
            if (code !in 200..299) Log.w("MotoRota", "HTTP $code ${url.substringBefore('?')}: ${text.take(200)}")
            return HttpResult(code, text)
        } catch (e: UnknownHostException) {
            return HttpResult(-1, "", "sem internet (não achou ${host(url)})")
        } catch (e: SocketTimeoutException) {
            return HttpResult(-1, "", "${host(url)} demorou demais")
        } catch (e: SSLException) {
            return HttpResult(-1, "", "falha de segurança na conexão (confira data e hora do celular)")
        } catch (e: IOException) {
            Log.w("MotoRota", "HTTP falhou ${url.substringBefore('?')}: $e")
            return HttpResult(-1, "", "conexão falhou (${e.message ?: e.javaClass.simpleName})")
        } finally {
            conn.disconnect()
        }
    }

    private fun host(url: String) = runCatching { URL(url).host }.getOrDefault("servidor")
}

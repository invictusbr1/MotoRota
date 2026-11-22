package com.motorota.data

import com.motorota.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val version: String, val apkUrl: String, val pageUrl: String)

/**
 * Confere na pagina de Releases do GitHub se existe uma versao mais nova do app.
 * O numero da versao e o numero da compilacao do GitHub Actions (versionCode).
 */
object Updater {
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val repo = BuildConfig.GITHUB_REPO
        if (repo.isBlank()) return@withContext null // compilado fora do GitHub (teste local)
        val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "MotoRota-Android")
        try {
            if (conn.responseCode != 200) return@withContext null
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = o.optString("tag_name") // ex.: v0.1.12
            val build = tag.substringAfterLast('.').toIntOrNull() ?: return@withContext null
            if (build <= BuildConfig.VERSION_CODE) return@withContext null
            val assets = o.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url")
            }
            UpdateInfo(tag.removePrefix("v"), apk ?: o.optString("html_url"), o.optString("html_url"))
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}

package com.motorota.data

import android.content.Context
import android.content.SharedPreferences
import com.motorota.BuildConfig

/**
 * Chaves de serviços externos. Ficam salvas só no celular (Ajustes > Assistente e serviços),
 * para não irem parar dentro do APK público. A da TomTom ainda aceita a do GitHub (TOMTOM_KEY).
 */
object Keys {
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("chaves", Context.MODE_PRIVATE)
    }

    val tomtom: String
        get() = prefs?.getString("tomtom", null)?.trim()?.takeIf { it.isNotEmpty() } ?: BuildConfig.TOMTOM_KEY

    val gemini: String
        get() = prefs?.getString("gemini", null)?.trim()?.takeIf { it.isNotEmpty() } ?: BuildConfig.GEMINI_KEY

    /** a chave já veio embutida no app (chaves.txt / segredos do GitHub) */
    val geminiBuiltIn: Boolean get() = BuildConfig.GEMINI_KEY.isNotBlank()
    val tomtomBuiltIn: Boolean get() = BuildConfig.TOMTOM_KEY.isNotBlank()

    fun geminiSaved(): String = prefs?.getString("gemini", null).orEmpty()

    fun tomtomSaved(): String = prefs?.getString("tomtom", null).orEmpty()

    fun save(tomtom: String, gemini: String) {
        prefs?.edit()
            ?.putString("tomtom", tomtom.trim())
            ?.putString("gemini", gemini.trim())
            ?.apply()
    }
}

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.motorota"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.motorota"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // "dono/MotoRota" quando compilado pelo GitHub Actions: usado para avisar de versao nova
        // chave da TomTom (transito ao vivo): no GitHub vem do segredo TOMTOM_KEY,
        // no computador vem do local.properties (tomtom.key=...). Nunca fica no codigo.
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        // chaves.txt (na pasta do projeto, NAO vai para o GitHub): GEMINI_KEY=... e TOMTOM_KEY=...
        val chaves = Properties().apply {
            rootProject.file("chaves.txt").takeIf { it.exists() }?.reader(Charsets.UTF_8)?.use { load(it) }
        }
        fun chave(nome: String, vararg extra: String?): String =
            (listOf(System.getenv(nome), chaves.getProperty(nome)) + extra)
                .firstOrNull { !it.isNullOrBlank() }?.trim() ?: ""
        val tomtomKey = chave("TOMTOM_KEY", localProps.getProperty("tomtom.key"))
        val geminiKey = chave("GEMINI_KEY")
        val groqKey = chave("GROQ_KEY")
        val githubRepo = System.getenv("GITHUB_REPOSITORY")?.takeIf { it.isNotBlank() } ?: "invictusbr1/MotoRota"
        buildConfigField("String", "TOMTOM_KEY", "\"" + tomtomKey + "\"")
        buildConfigField("String", "GEMINI_KEY", "\"" + geminiKey + "\"")
        buildConfigField("String", "GROQ_KEY", "\"" + groqKey + "\"")
        buildConfigField("String", "GITHUB_REPO", "\"" + githubRepo + "\"")
        ndk { abiFilters += ((project.findProperty("abis") as String?) ?: "arm64-v8a,armeabi-v7a").split(",") }
    }

    signingConfigs {
        // Chave fixa para que as atualizacoes instalem por cima da versao anterior.
        create("shared") {
            storeFile = rootProject.file("keystore/motorota.jks")
            storePassword = "motorota"
            keyAlias = "motorota"
            keyPassword = "motorota"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(project(":brouter"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("org.maplibre.gl:android-sdk:13.7.1")

    // Jarvis: reconhece a palavra "Jarvis" offline no celular
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // Voz do Jarvis offline e sem limite (Piper / sherpa-onnx). O .aar é baixado pelo GitHub Actions
    // (ou pelo script baixar-voz-offline) para app/libs, porque é grande demais para o repositório.
    implementation(files("libs/sherpa-onnx.aar"))
    // descompactar o modelo de voz (.tar.bz2) baixado na primeira vez
    implementation("org.apache.commons:commons-compress:1.26.2")

    // Intercomunicador: conexão em tempo real com a central
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

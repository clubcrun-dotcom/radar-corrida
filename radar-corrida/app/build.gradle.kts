plugins {
    id("com.android.application")
}

// Número da versão: no GitHub Actions usa o número da execução, para cada APK novo
// instalar por cima do anterior. Localmente fica 1.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Chave de assinatura fixa (necessária para atualizar o app sem desinstalar).
// Vem do GitHub Secrets via variáveis de ambiente; sem ela, assina com a chave de debug.
val keystorePath: String? = System.getenv("RADAR_KEYSTORE")
val keystorePassword: String = System.getenv("RADAR_KEYSTORE_PASSWORD") ?: ""
val hasReleaseKey = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "br.leno.radarcorrida"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.leno.radarcorrida"
        minSdk = 22          // Android 5.1+: roda em celulares e tablets antigos
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.3.$buildNumber"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = "radar"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            // Encolhe e otimiza o código: APK final na casa de dezenas de KB.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Sem dependências de bibliotecas no app: nada de AndroidX, Kotlin ou Google Play Services.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

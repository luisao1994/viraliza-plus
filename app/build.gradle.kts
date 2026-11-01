plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.viraliza.plus"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.viraliza.plus"
        minSdk = 24
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Chave fixa: cada APK novo instala por cima do anterior.
    signingConfigs {
        create("viraliza") {
            storeFile = file("viraliza.keystore")
            storePassword = "viraliza123"
            keyAlias = "viraliza"
            keyPassword = "viraliza123"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("viraliza")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("viraliza")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

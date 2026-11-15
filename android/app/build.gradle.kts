plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.instawasap.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.instawasap.app"
        minSdk = 24
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // Una APK por tipo de procesador (más liviana). arm64-v8a sirve para casi todos los celulares actuales.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("instawasap.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "instawasap"
            keyAlias = "instawasap"
            keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: "instawasap"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // yt-dlp/jackson no se llevan bien con R8
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Los binarios (python, ffmpeg) se ejecutan desde la carpeta de librerías nativas:
    // tienen que quedar extraídos en el dispositivo.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(project(":comun")) // yt-dlp, ffmpeg, stickers y compartir
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

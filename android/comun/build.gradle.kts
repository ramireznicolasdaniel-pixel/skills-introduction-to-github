plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.instawasap.comun"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    val ytdl = "0.18.1"
    // yt-dlp + Python + ffmpeg empaquetados para Android (ffmpeg necesita librerías del paquete de Python)
    api("io.github.junkfood02.youtubedl-android:library:$ytdl")
    api("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")
    api("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

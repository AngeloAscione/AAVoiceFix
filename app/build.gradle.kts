plugins {
    id("com.android.application")
}

android {
    namespace = "dev.local.aawafixer"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.local.aawafixer"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    // Stato ufficiale della connessione Android Auto (proiezione / nativo).
    implementation("androidx.car.app:app:1.7.0")
    // Decoder Opus/Ogg affidabile anche su device vecchi; audio focus gestito da noi.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    // Fallback SAF: Accesso alla cartella senza MANAGE_EXTERNAL_STORAGE.
    implementation("androidx.documentfile:documentfile:1.1.0")

    testImplementation("junit:junit:4.13.2")
}

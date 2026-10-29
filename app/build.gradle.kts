import java.util.Properties

plugins {
    id("com.android.application")
}

// La firma release vive fuori dal repository: keystore.properties e il
// keystore sono ignorati da git. Senza il file, assembleRelease produce un
// APK non firmato (utile per build di terzi).
val signingPropertiesFile = rootProject.file("keystore.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.exists()) {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "dev.local.aawafixer"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.local.aawafixer"
        minSdk = 35
        targetSdk = 37
        versionCode = 5
        versionName = "1.2.2"
    }

    signingConfigs {
        if (signingPropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Scelta CEO: progetto open, nessuna offuscazione.
            isMinifyEnabled = false
            isShrinkResources = false
            if (signingPropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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

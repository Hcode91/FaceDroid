import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hcode91.facedroid"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hcode91.facedroid"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Fixed debug key committed to the repo, so every CI build installs over the last one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    androidResources {
        noCompress += "task"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}

// MediaPipe face landmark model (~3.6 MB) is fetched at build time rather than committed.
val faceModel = file("src/main/assets/face_landmarker.task")
val downloadFaceModel by tasks.registering {
    outputs.file(faceModel)
    onlyIf { !faceModel.exists() }
    doLast {
        faceModel.parentFile.mkdirs()
        val url = "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
        URI(url).toURL().openStream().use { input ->
            faceModel.outputStream().use { input.copyTo(it) }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadFaceModel) }

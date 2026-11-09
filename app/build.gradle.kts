import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
}

android {
    namespace = "com.derinliksaati"
    // Newest SDK installed in Android Studio (SDK Manager > SDK Platforms).
    compileSdk = 37

    defaultConfig {
        applicationId = "com.derinliksaati"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    // Key and password stay outside the repository. Without them, release builds come out unsigned.
    val home = System.getProperty("user.home")
    val keystore = file("$home/tpw-release.keystore")
    val passwordFile = file("$home/tpw-signing.txt")
    if (keystore.exists() && passwordFile.exists()) {
        val password = passwordFile.readText().removePrefix("﻿").trim()
        signingConfigs.create("release") {
            storeFile = keystore
            storePassword = password
            keyAlias = "tpw"
            keyPassword = password
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    // The inpainting model is memory-mapped straight from the APK, so it must not be compressed.
    androidResources { noCompress += "tflite" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    // Selfie segmentation ships its model inside the APK, so it works with no network.
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    // Runs the LaMa inpainting model (in assets) on the device.
    implementation("com.google.ai.edge.litert:litert:2.3.2")
}

// The inpainting model is too large for Git, so the first build fetches it and checks its hash.
val modelFile = file("src/main/assets/lama_dilated.tflite")
val downloadModel = tasks.register("downloadModel") {
    onlyIf { !modelFile.exists() }
    doLast {
        val url = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/lama_dilated/releases/v0.66.0/lama_dilated-tflite-float.zip"
        val expected = "fd520b4daf0d7031d233a1a1a8a39b09a13eafc789ca469e8692b270391f7280"
        val zip = File(temporaryDir, "model.zip")
        logger.lifecycle("Downloading the inpainting model (170 MB)...")
        URI(url).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256")
        zip.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) { "Model download failed its hash check: $actual" }
        val unpacked = File(temporaryDir, "model.tflite")
        ZipFile(zip).use { z ->
            val entry = z.entries().asSequence().first { it.name.endsWith("lama_dilated.tflite") }
            z.getInputStream(entry).use { input -> unpacked.outputStream().use { input.copyTo(it) } }
        }
        // Copied only after the hash check and a full unpack, so a failed run leaves no partial model behind.
        unpacked.copyTo(modelFile, overwrite = true)
        zip.delete()
        unpacked.delete()
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(downloadModel) }

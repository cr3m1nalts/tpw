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

// The models are too large for Git, so the first build fetches each one and checks its hash.
fun modelDownload(task: String, fileName: String, url: String, sha256: String) = tasks.register(task) {
    val target = file("src/main/assets/$fileName")
    onlyIf { !target.exists() }
    doLast {
        val zip = File(temporaryDir, "model.zip")
        logger.lifecycle("Downloading $fileName...")
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
        check(actual == sha256) { "$fileName download failed its hash check: $actual" }
        val unpacked = File(temporaryDir, fileName)
        ZipFile(zip).use { z ->
            val entry = z.entries().asSequence().first { it.name.endsWith(fileName) }
            z.getInputStream(entry).use { input -> unpacked.outputStream().use { input.copyTo(it) } }
        }
        // Copied only after the hash check and a full unpack, so a failed run leaves no partial model behind.
        unpacked.copyTo(target, overwrite = true)
        zip.delete()
        unpacked.delete()
    }
}

val modelDownloads = listOf(
    modelDownload(
        "downloadInpaintModel", "lama_dilated.tflite",
        "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/lama_dilated/releases/v0.66.0/lama_dilated-tflite-float.zip",
        "fd520b4daf0d7031d233a1a1a8a39b09a13eafc789ca469e8692b270391f7280",
    ),
    modelDownload(
        "downloadDepthModel", "depth_anything_v2.tflite",
        "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/depth_anything_v2/releases/v0.66.0/depth_anything_v2-tflite-float.zip",
        "98ac01249bb6e8e7fd9e75e73e82094ad61b7442fdd7a0f830c7d8aa5f15208f",
    ),
)
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(modelDownloads) }

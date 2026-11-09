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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    // Selfie segmentation ships its model inside the APK, so it works with no network.
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
}

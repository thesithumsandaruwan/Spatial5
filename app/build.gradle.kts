import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release key from signing/keystore.properties (git-ignored; CI writes it from secrets).
// Without it, release builds are signed with the local debug key.
val signingProps = Properties().apply {
    val f = rootProject.file("signing/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.headtracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.headtracker"
        minSdk = 29          // playback capture (immersive mode) needs Android 10
        targetSdk = 35
        // Release builds pass these from the git tag (see .github/workflows/release.yml).
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 2
        versionName = (findProperty("appVersionName") as String?) ?: "1.1"
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file("signing/" + signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = false }
}

dependencies {
    val camerax = "1.4.2"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")   // springs (~30 KB)
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    // Face model is provided by Google Play services: keeps the APK tiny (a few MB instead of ~40 MB).
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
    testImplementation("junit:junit:4.13.2")
}

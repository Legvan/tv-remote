plugins {
    id("com.android.application")
    // org.jetbrains.kotlin.android is applied automatically by AGP 9.x (builtInKotlin=true)
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.porter.tvremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.porter.tvremote"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.5"
    }

    val releaseKeystorePath = providers.gradleProperty("TV_REMOTE_KEYSTORE_PATH").orNull
    val releaseKeystorePassword = providers.gradleProperty("TV_REMOTE_KEYSTORE_PASSWORD").orNull
    val releaseKeyAlias = providers.gradleProperty("TV_REMOTE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.gradleProperty("TV_REMOTE_KEY_PASSWORD").orNull
    val hasReleaseSigning = listOf(
        releaseKeystorePath,
        releaseKeystorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { it != null }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

}

// F-Droid build server provides JDK 21 only (auto-provisioning disabled)
kotlin {
    jvmToolchain(21)
}

val ktorVersion = "2.3.12"

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Leanback for Android TV UI
    implementation("androidx.leanback:leanback:1.0.0")

    // Ktor embedded HTTP server (CIO engine — Android-compatible, coroutine-based)
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // AdbLib — pure-Java ADB protocol client via JitPack (used by ADBRemoteATV)
    // Used to connect to the TV's own ADB daemon at 127.0.0.1:5555
    // Pinned to specific commit (no tags exist on upstream repo; stable since 2017)
    implementation("com.github.cgutman:AdbLib:d6937951eb98557c76ee2081e383d50886ce109a")

    // JSON serialization (1.7.x supports Kotlin 2.x)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}

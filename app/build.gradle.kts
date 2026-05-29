import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Read TURN credentials out of `local.properties` (gitignored) so they don't
// end up in version control. Mint them from the Cloudflare dashboard — see
// the root README for the signup flow. Empty defaults are fine: the app falls
// back to STUN-only when the strings are blank.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val turnUsername = localProps.getProperty("TURN_USERNAME", "")
val turnCredential = localProps.getProperty("TURN_CREDENTIAL", "")

android {
    namespace = "org.crainiate.bluesteal"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.crainiate.bluesteal"
        minSdk = 26                // Android 8.0 — solid BluetoothLeAdvertiser support
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "TURN_USERNAME", "\"$turnUsername\"")
        buildConfigField("String", "TURN_CREDENTIAL", "\"$turnCredential\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true   // expose TURN_USERNAME / TURN_CREDENTIAL to Kotlin
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties"
            )
        }
    }
}

dependencies {
    // Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Android core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Kotlin std
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Signaling
    implementation(libs.hivemq.mqtt.client)
    implementation(libs.slf4j.android)

    // WebRTC
    implementation(libs.webrtc.sdk)

    // caBLE tunnel side-channel: WebSocket transport + EC math
    implementation(libs.okhttp)
    implementation(libs.bouncycastle.prov)

    // Tooling
    debugImplementation(libs.androidx.ui.tooling)
}

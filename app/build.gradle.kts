import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Rendezvous config comes from local.properties (gitignored) so the base URL
// and bearer are never committed to this public repo. Blank by default
// (rendezvous disabled). See local.properties.example.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun rendezvousProp(key: String): String =
    (localProps.getProperty(key) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "org.crainiate.bluesteal"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.crainiate.bluesteal"
        minSdk = 26                // Android 8.0 — solid BluetoothLeAdvertiser support
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.5"

        buildConfigField("String", "RENDEZVOUS_BASE_URL", "\"${rendezvousProp("rendezvous.baseUrl")}\"")
        buildConfigField("String", "RENDEZVOUS_KEY", "\"${rendezvousProp("rendezvous.key")}\"")
        buildConfigField("String", "RENDEZVOUS_ROOM", "\"${rendezvousProp("rendezvous.room")}\"")
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
        buildConfig = true
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

    // caBLE tunnel side-channel: WebSocket transport + EC math
    implementation(libs.okhttp)
    implementation(libs.bouncycastle.prov)

    // QR scan (camera) + generation
    implementation(libs.zxing.embedded)

    // Tooling
    debugImplementation(libs.androidx.ui.tooling)
}

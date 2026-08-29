import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Signing details are read from keystore.properties, which is deliberately not in
// the repository. The keystore and its passwords stay on the machine that builds --
// they never need to be shared, and anyone who has them can publish as this app.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}
val hasReleaseKey = keystorePropertiesFile.exists()

android {
    namespace = "com.streamer.timetable"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.streamer.timetable"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Left unminified deliberately: R8 with Room, OkHttp and kotlinx
            // serialization needs keep rules that cannot be verified without running
            // the app, and a stripped class would fail at runtime rather than build.
            // The win here is a non-debuggable build, which is where most of the
            // animation smoothness comes from.
            isMinifyEnabled = false

            // The real key when keystore.properties is present, the debug key
            // otherwise so a checkout without it still builds. Note that switching
            // between the two forces testers to uninstall: Android refuses to upgrade
            // an app to a differently-signed build.
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        // Crash reports name the exact build they came from.
        buildConfig = true
    }
}

// Room writes its expected schema out as JSON, which is what the migration SQL is
// checked against rather than being hand-written from memory.
ksp { arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.path) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Glance renders home-screen widgets with a Compose-like API. Widgets run in the
    // launcher's process via RemoteViews, so ordinary Compose cannot be used there.
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

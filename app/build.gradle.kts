plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.anlagen.solaruebersicht"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.anlagen.solaruebersicht"
        minSdk = 26            // Android 8.0
        targetSdk = 37
        versionCode = 17
        versionName = "1.10.0"
        // Testkopie neben der echten App (gradlew ... -Ptestkopie): eigene Kennung, eigene Daten
        if (project.hasProperty("testkopie")) {
            applicationIdSuffix = ".test"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Für den privaten Gebrauch mit dem Debug-Schlüssel dieses Rechners signiert
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // Vorschau der Bildschirme in Android Studio (Datei Previews.kt, Ansicht "Split"/"Design")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}

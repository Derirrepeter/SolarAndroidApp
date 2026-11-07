// Versionen der Build-Werkzeuge für das ganze Projekt
// (Android-Gradle-Plugin 9 bringt die Kotlin-Unterstützung selbst mit)
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

// Build-Ausgaben außerhalb von Synology Drive ablegen (die Synchronisierung blockiert sonst Dateien)
allprojects {
    layout.buildDirectory.set(file(System.getProperty("user.home") + "/.solaruebersicht-build/" + name))
}

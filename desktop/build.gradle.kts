import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// The Windows/macOS/Linux app: a window around the shared Compose UI, with
// the Rust core loaded from this module's resources instead of jniLibs.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "dev.nexfy.desktop.MainKt"
        // Where :shared leaves the host build of the core, so JNA finds it
        // the way it finds jniLibs on the phone. A packaged app carries the
        // library itself instead.
        jvmArgs += "-Djna.library.path=" + rootProject.file("core/target/android-dev").path
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Nexfy"
            packageVersion = (findProperty("versionName") as String?) ?: "1.0.0"
        }
    }
}

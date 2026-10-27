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
    // Windows keeps the animations of a window behind its styles, which an
    // undecorated one is missing (see WindowsChrome.kt).
    implementation(libs.jna)
    implementation(libs.jna.platform)
}

/**
 * The directory JNA looks in on the classpath for a bundled library:
 * `win32-x86-64`, `darwin-aarch64`, `linux-x86-64`. Putting the core there
 * makes `Native.register` find it both from Gradle and from an installed
 * app, with no library path to set.
 */
val jnaPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = when (val a = System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86-64"
        "aarch64", "arm64" -> "aarch64"
        else -> a
    }
    val prefix = when {
        os.startsWith("windows") -> "win32"
        os.startsWith("mac") -> "darwin"
        else -> "linux"
    }
    "$prefix-$arch"
}

/** Carries the host build of the core into the app's own resources. */
val bundleRustCore by tasks.registering(Copy::class) {
    dependsOn(":shared:buildRustCoreHost")
    from(rootProject.file("core/target/android-dev/${System.mapLibraryName("nexfy_core")}"))
    into(layout.buildDirectory.dir("generated/core/$jnaPlatform"))
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/core"))
}

tasks.named("processResources") { dependsOn(bundleRustCore) }

compose.desktop {
    application {
        mainClass = "dev.nexfy.desktop.MainKt"
        nativeDistributions {
            // The runtime jlink builds carries only the root locale unless this
            // is asked for, and the app is written in Portuguese: without it a
            // date formatted "d 'de' MMM" with a pt-BR locale comes out
            // "16 de Oct", because the month names fall back to English.
            modules("jdk.localedata")
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Nexfy"
            packageVersion = (findProperty("versionName") as String?) ?: "1.0.0"
            windows {
                // Drawn by icons/MakeIcons.java from the phone's launcher icon.
                iconFile.set(project.file("icons/nexfy.ico"))
                menuGroup = "Nexfy"
                shortcut = true
                perUserInstall = true
                // Fixed for good: an installer with a new one would install a
                // second Nexfy beside the first instead of updating it.
                upgradeUuid = "6f1d9c2e-4b7a-4e8f-9a51-2c7e3d0b8a64"
            }
        }
        // A music player needs little heap: a ceiling keeps the JVM from
        // claiming a quarter of the computer's memory, which is what it does
        // unasked, and the covers' memory cache is sized from it.
        jvmArgs("-Xms96m", "-Xmx640m", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=20")
        // `gradlew :desktop:run -Pperf`: the window times its own frames (PerfTour.kt).
        if (project.hasProperty("perf")) jvmArgs("-Dnexfy.perf=true")
        // `-PperfJfr=<file>`: a Flight Recorder profile of the same run, to see where the frames go.
        (findProperty("perfJfr") as String?)?.let { jvmArgs("-XX:StartFlightRecording=duration=120s,settings=profile,filename=$it") }
    }
}

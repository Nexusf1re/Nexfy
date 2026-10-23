plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// The Rust core for this computer, plus the UniFFI bindings both targets
// read. The Android ABIs are still built by :app into jniLibs; this is the
// host library the desktop JVM loads through JNA.
val coreDir: File = rootProject.file("core")
val hostProfile = "android-dev"
val hostLibrary: File = coreDir.resolve("target/$hostProfile/${System.mapLibraryName("nexfy_core")}")
val bindingsDir: File = file("src/commonJvmMain/kotlin")

val cargo: String = File(
    System.getProperty("user.home"),
    ".cargo/bin/cargo" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "",
).path

val buildRustCoreHost by tasks.registering(Exec::class) {
    group = "rust"
    description = "Builds core/ for this computer (no --target: cargo picks the host)."
    inputs.dir(coreDir.resolve("src"))
    inputs.dir(coreDir.resolve("third_party"))
    inputs.files(coreDir.resolve("Cargo.toml"), coreDir.resolve("Cargo.lock"))
    outputs.file(hostLibrary)
    workingDir = coreDir
    commandLine(cargo, "build", "--profile", hostProfile)
}

val generateUniffiBindingsShared by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generates the Kotlin bindings both targets compile against."
    dependsOn(buildRustCoreHost)
    inputs.file(hostLibrary)
    outputs.dir(bindingsDir.resolve("uniffi"))
    workingDir = coreDir
    commandLine(
        cargo, "run", "--release", "--manifest-path", "uniffi-bindgen/Cargo.toml", "--",
        "generate", "--library", hostLibrary.path,
        "--language", "kotlin", "--out-dir", bindingsDir.path, "--no-format",
    )
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateUniffiBindingsShared)
}

// The screens both apps show. Everything they need from the platform (the
// glass shader, the artwork palette, opening a link, the back gesture) is
// declared expect in commonJvmMain and answered per target.
kotlin {
    androidLibrary {
        namespace = "dev.nexfy.shared"
        // Compose Multiplatform 1.12 ships the androidx.compose 1.12
        // artifacts, which refuse consumers below 37.
        compileSdk = 37
        minSdk = 31
    }

    jvm("desktop")

    // Both targets run on a JVM, so they share one source set below
    // commonMain: it reaches java.* and JNA, which the UniFFI bindings and
    // the file-backed settings need and commonMain cannot see.
    applyDefaultHierarchyTemplate()
    sourceSets {
        val commonJvmMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(commonJvmMain)
        named("desktopMain").get().dependsOn(commonJvmMain)

        // Compose, Coil and Haze stand in this module's own signatures (a
        // screen takes a PlatformContext, the theme carries a Look), so the
        // apps that show these screens compile against them too.
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.materialIconsExtended)
            api(compose.ui)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            // The predictive back gesture and the jank measurements, both of
            // which only Android has.
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.metrics.performance)
        }
        commonJvmMain.dependencies {
            // UniFFI's bindings call the core through JNA.
            implementation(libs.jna)
            api(libs.coil.compose)
            api(libs.coil.network.okhttp)
            api(libs.haze)
        }
    }
}

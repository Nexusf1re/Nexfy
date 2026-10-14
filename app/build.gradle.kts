import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Rust core (../core) builds into jniLibs and its UniFFI Kotlin bindings
// into src/main/java/uniffi before every build. Both outputs are gitignored,
// and both tasks are skipped while their inputs are unchanged.
val ndkVersionPinned = "30.0.16248370"
val coreDir: File = rootProject.file("core")
val jniLibsDir: File = file("src/main/jniLibs")
val coreLibrary: File = jniLibsDir.resolve("arm64-v8a/libnexfy_core.so")
val bindingsDir: File = file("src/main/java/uniffi")

// Full LTO only when a release APK is requested; debug builds use the
// faster android-dev profile (see core/Cargo.toml).
val rustProfile: String =
    if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) "release"
    else "android-dev"

val sdkDir: String = Properties()
    .apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
    .getProperty("sdk.dir")
    ?: System.getenv("ANDROID_HOME")
    ?: error("Android SDK not found: set sdk.dir in local.properties")

val cargo: String = File(
    System.getProperty("user.home"),
    ".cargo/bin/cargo" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "",
).path

val buildRustCore by tasks.registering(Exec::class) {
    group = "rust"
    description = "Builds core/ for arm64 Android into jniLibs."
    inputs.dir(coreDir.resolve("src"))
    inputs.dir(coreDir.resolve("third_party"))
    inputs.files(coreDir.resolve("Cargo.toml"), coreDir.resolve("Cargo.lock"))
    inputs.property("profile", rustProfile)
    outputs.file(coreLibrary)
    workingDir = coreDir
    environment("ANDROID_NDK_HOME", "$sdkDir/ndk/$ndkVersionPinned")
    // Optimised even for debug APKs: a debug librespot cannot decode in real time.
    commandLine(
        cargo, "ndk", "-t", "arm64-v8a", "--platform", "31", "-o", jniLibsDir.path,
        "build", "--profile", rustProfile,
    )
}

val generateUniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generates the Kotlin bindings for the core library."
    dependsOn(buildRustCore)
    inputs.file(coreLibrary)
    outputs.dir(bindingsDir)
    workingDir = coreDir
    commandLine(
        cargo, "run", "--release", "--manifest-path", "uniffi-bindgen/Cargo.toml", "--",
        "generate", "--library", coreLibrary.path,
        "--language", "kotlin", "--out-dir", bindingsDir.parentFile.path, "--no-format",
    )
}

tasks.named("preBuild") { dependsOn(generateUniffiBindings) }

android {
    namespace = "dev.nexfy.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    ndkVersion = ndkVersionPinned

    defaultConfig {
        applicationId = "dev.nexfy.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The core is only built for arm64 (S23+); keep JNA's other ABIs out.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            // Sideloaded only: signed with the debug key, optimised and shrunk by R8.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.media3.session)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.haze)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    // UniFFI's Kotlin bindings call the core through JNA.
    implementation("${libs.jna.get()}@aar")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

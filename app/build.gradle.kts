import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Rust core (../core) builds into jniLibs and its UniFFI Kotlin bindings
// into src/main/java/uniffi before every build. Both outputs are gitignored.
val ndkVersionPinned = "30.0.16248370"
val coreDir: File = rootProject.file("core")
val jniLibsDir: File = file("src/main/jniLibs")
val coreLibrary: File = jniLibsDir.resolve("arm64-v8a/libsonata_core.so")

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
    workingDir = coreDir
    environment("ANDROID_NDK_HOME", "$sdkDir/ndk/$ndkVersionPinned")
    // Release even for debug APKs: a debug librespot cannot decode in real time.
    commandLine(cargo, "ndk", "-t", "arm64-v8a", "--platform", "31", "-o", jniLibsDir.path, "build", "--release")
}

val generateUniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generates the Kotlin bindings for the core library."
    dependsOn(buildRustCore)
    workingDir = coreDir
    commandLine(
        cargo, "run", "--release", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", coreLibrary.path,
        "--language", "kotlin", "--out-dir", file("src/main/java").path, "--no-format",
    )
}

tasks.named("preBuild") { dependsOn(generateUniffiBindings) }

android {
    namespace = "dev.sonata.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    ndkVersion = ndkVersionPinned

    defaultConfig {
        applicationId = "dev.sonata.app"
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
            isMinifyEnabled = false
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

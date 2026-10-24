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
// Phones only by default; -PABIS="arm64-v8a,x86_64" adds the emulator's.
val abis: List<String> =
    ((findProperty("ABIS") as String?) ?: "arm64-v8a").split(",").map { it.trim() }.filter { it.isNotEmpty() }
val coreLibrary: File = jniLibsDir.resolve("${abis[0]}/libnexfy_core.so")
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
    description = "Builds core/ for the chosen Android ABIs into jniLibs."
    inputs.dir(coreDir.resolve("src"))
    inputs.dir(coreDir.resolve("third_party"))
    inputs.files(coreDir.resolve("Cargo.toml"), coreDir.resolve("Cargo.lock"))
    inputs.property("profile", rustProfile)
    inputs.property("abis", abis)
    outputs.file(coreLibrary)
    workingDir = coreDir
    environment("ANDROID_NDK_HOME", "$sdkDir/ndk/$ndkVersionPinned")
    // Optimised even for debug APKs: a debug librespot cannot decode in real time.
    commandLine(
        listOf(cargo, "ndk") + abis.flatMap { listOf("-t", it) } +
            listOf("--platform", "31", "-o", jniLibsDir.path, "build", "--profile", rustProfile),
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

// Release signing: a keystore kept outside the repo, given as Gradle properties
// (~/.gradle/gradle.properties) or environment variables (CI). Without one,
// release builds are signed with the debug key (also with -PNEXFY_KEYSTORE=).
fun secret(name: String): String? = ((findProperty(name) as String?) ?: System.getenv(name))?.takeIf { it.isNotBlank() }
val releaseKeystore: String? = secret("NEXFY_KEYSTORE")

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
        // Releases pass these from the tag (v0.2.0) and the CI run number.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only what the core was built for; keeps JNA's other ABIs out.
        ndk { abiFilters += abis }
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = secret("NEXFY_KEYSTORE_PASSWORD")
                keyAlias = secret("NEXFY_KEY_ALIAS")
                keyPassword = secret("NEXFY_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            // Optimised and shrunk by R8; signed with the release key when one is configured.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
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
    implementation(libs.androidx.metrics.performance)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    // UniFFI's Kotlin bindings call the core through JNA.
    // asProvider(): the catalog also holds jna-platform, which makes `libs.jna`
    // the pair of them until this asks for the one named plainly.
    implementation("${libs.jna.asProvider().get()}@aar")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

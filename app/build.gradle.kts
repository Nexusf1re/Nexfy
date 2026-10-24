import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Rust core (../core) builds into jniLibs before every build; the output
// is gitignored, and the task is skipped while its inputs are unchanged. The
// core's UniFFI bindings are :shared's, which generates them for both targets.
val ndkVersionPinned = "30.0.16248370"
val coreDir: File = rootProject.file("core")
val jniLibsDir: File = file("src/main/jniLibs")
// Phones only by default; -PABIS="arm64-v8a,x86_64" adds the emulator's.
val abis: List<String> =
    ((findProperty("ABIS") as String?) ?: "arm64-v8a").split(",").map { it.trim() }.filter { it.isNotEmpty() }
val coreLibrary: File = jniLibsDir.resolve("${abis[0]}/libnexfy_core.so")

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

// The .so still has to be in jniLibs before the APK is assembled; the
// bindings that call into it come from :shared.
tasks.named("preBuild") { dependsOn(buildRustCore) }

// Release signing: a keystore kept outside the repo, given as Gradle properties
// (~/.gradle/gradle.properties) or environment variables (CI). Without one,
// release builds are signed with the debug key (also with -PNEXFY_KEYSTORE=).
fun secret(name: String): String? = ((findProperty(name) as String?) ?: System.getenv(name))?.takeIf { it.isNotBlank() }
val releaseKeystore: String? = secret("NEXFY_KEYSTORE")

android {
    namespace = "dev.nexfy.app"
    // 37 because :shared compiles against it: a library's compile level is a
    // floor for everything that depends on it. What the app asks of the system
    // at run time is targetSdk below, and that is left where it was.
    compileSdk {
        version = release(37)
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
    // Without the exclude the plain jna jar arrives through :shared and every
    // com.sun.jna class is on the APK's classpath twice: once from the jar,
    // once from the aar below, which is the one that carries Android natives.
    implementation(project(":shared")) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
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

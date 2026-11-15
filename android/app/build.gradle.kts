plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The ABIs the engine is built for: `-Pwavo.abis=arm64-v8a,x86_64` adds the emulator's.
val abis = (findProperty("wavo.abis") as String? ?: "arm64-v8a").split(",")

android {
    namespace = "io.github.tyetladd.wavo"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.tyetladd.wavo"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += abis }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key until there is a release one: installable, not for a store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

// The engine: core/ built by cargo-ndk into jniLibs, before anything else.
val cargoNdk = tasks.register<Exec>("cargoNdk") {
    workingDir = rootProject.file("core")
    environment("ANDROID_NDK_HOME", android.ndkDirectory.path)
    commandLine(
        listOf("cargo", "ndk") + abis.flatMap { listOf("-t", it) } +
            listOf("--platform", "29", "-o", file("src/main/jniLibs").path, "build", "--release", "--locked"),
    )
}

tasks.named("preBuild") { dependsOn(cargoNdk) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

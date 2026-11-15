import java.security.MessageDigest

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
        versionCode = 5
        versionName = "0.3.0"
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

    // Of sherpa-onnx only its JNI library and ONNX Runtime are used.
    packaging {
        jniLibs.excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
    }
}

// sherpa-onnx, for the speakers of a conversation: its AAR is only on GitHub's releases, and no
// binary is kept in git, so it is downloaded once into libs/ and checked.
val sherpaVersion = "1.13.8"
val sherpaSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar")

val sherpa = tasks.register("sherpaAar") {
    outputs.file(sherpaAar)
    doLast {
        val file = sherpaAar.asFile
        file.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
        uri(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val sha256 = digest.digest().joinToString("") { byte -> String.format("%02x", byte) }
        if (sha256 != sherpaSha256) {
            file.delete()
            throw GradleException("sherpa-onnx $sherpaVersion: sha256 is $sha256, not $sherpaSha256")
        }
    }
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

tasks.named("preBuild") { dependsOn(cargoNdk, sherpa) }

dependencies {
    implementation(files(sherpaAar))
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

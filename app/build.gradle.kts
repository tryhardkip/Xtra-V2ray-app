plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.fkvpn"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.fkvpn"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // Build native libs only for these ABIs.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Native libs are produced by cargo-ndk into src/main/jniLibs (see :cargoBuild).
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}

// ---------------------------------------------------------------------------
// Cross-compile the Rust core with cargo-ndk and drop the .so into jniLibs.
// Requires: rustup targets aarch64-linux-android + armv7-linux-androideabi,
//           `cargo install cargo-ndk`, and ANDROID_NDK_HOME set.
// ---------------------------------------------------------------------------
val cargoBuild by tasks.registering(Exec::class) {
    workingDir = file("${rootDir}/rust")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-o", "${projectDir}/src/main/jniLibs",
        "build", "--release"
    )
}

tasks.named("preBuild").configure {
    dependsOn(cargoBuild)
}

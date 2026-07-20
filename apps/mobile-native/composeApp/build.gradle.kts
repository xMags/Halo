plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.application")
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    // The same common shell that iOS hosts via MainViewController is hosted
    // on Android by MainActivity.
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
    }

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
            implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            implementation("org.jetbrains.compose.ui:ui:1.11.1")
            implementation("io.ktor:ktor-client-core:3.5.1")
            implementation("io.ktor:ktor-client-content-negotiation:3.5.1")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:3.5.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.5.1")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.9.3")
            implementation("androidx.core:core-ktx:1.13.1")
            implementation("io.ktor:ktor-client-okhttp:3.5.1")
            // Prebuilt libmpv (mpv-android lineage): provenance-checked but
            // emulator-only. The shipping Android build replaces it with an
            // owned reproducible libmpv build, like iOS's patched MPVKit.
            implementation("dev.jdtech.mpv:libmpv:1.0.0")
        }
        // Instrumented Compose UI tests — the Android parallel to the iOS
        // XCUITests. Drive the real MainActivity (real libmpv core) by semantics,
        // asserting playback/ownership without any coordinate tapping.
        androidInstrumentedTest.dependencies {
            implementation("org.jetbrains.compose.ui:ui-test:1.11.1")
            implementation("org.jetbrains.compose.ui:ui-test-junit4:1.11.1")
            implementation("androidx.test.ext:junit:1.2.1")
            implementation("androidx.test:runner:1.6.2")
        }
    }
}

android {
    namespace = "moe.ditto.halo"
    compileSdk = 36

    defaultConfig {
        applicationId = "moe.ditto.halo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Emulator is x86_64; keep arm64 so an eventual device install works.
        ndk {
            abiFilters += listOf("x86_64", "arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("debug") {
            isDefault = true
        }
    }

    // The two libmpv .so's under jni/ ship uncompressed already; nothing to strip.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

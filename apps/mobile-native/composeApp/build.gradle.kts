import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class ValidateReleaseSigningTask : DefaultTask() {
    @get:Input
    abstract val validationProblems: ListProperty<String>

    @TaskAction
    fun validateSigning() {
        val problems = validationProblems.get()
        check(problems.isEmpty()) {
            buildString {
                appendLine("Release signing is not configured:")
                problems.forEach { appendLine("- $it") }
                append("Configure Halo release signing in Gradle user properties or environment variables.")
            }
        }
    }
}

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.application")
}

// Halo's own credentials only. A fallback to another app's keystore would sign
// a release with the wrong identity, and nothing downstream would say so.
val releaseStoreFile = providers.gradleProperty("haloReleaseStoreFile")
    .orElse(providers.environmentVariable("HALO_RELEASE_STORE_FILE"))
val releaseStorePassword = providers.gradleProperty("haloReleaseStorePassword")
    .orElse(providers.environmentVariable("HALO_RELEASE_STORE_PASSWORD"))
val releaseKeyAlias = providers.gradleProperty("haloReleaseKeyAlias")
    .orElse(providers.environmentVariable("HALO_RELEASE_KEY_ALIAS"))
val releaseKeyPassword = providers.gradleProperty("haloReleaseKeyPassword")
    .orElse(providers.environmentVariable("HALO_RELEASE_KEY_PASSWORD"))

val releaseStoreFileValue = releaseStoreFile.orNull
val releaseStorePasswordValue = releaseStorePassword.orNull
val releaseKeyAliasValue = releaseKeyAlias.orNull
val releaseKeyPasswordValue = releaseKeyPassword.orNull
val releaseSigningProblems = buildList {
    if (releaseStoreFileValue.isNullOrBlank()) {
        add("release store file is missing or blank")
    } else if (!file(releaseStoreFileValue).isFile) {
        add("release store file must point to an existing regular file")
    }
    if (releaseStorePasswordValue.isNullOrBlank()) add("release store password is missing or blank")
    if (releaseKeyAliasValue.isNullOrBlank()) add("release key alias is missing or blank")
    if (releaseKeyPasswordValue.isNullOrBlank()) add("release key password is missing or blank")
}
val releaseSigningReady = releaseSigningProblems.isEmpty()

val validateReleaseSigning = tasks.register<ValidateReleaseSigningTask>("validateReleaseSigning") {
    validationProblems.set(releaseSigningProblems)
}
val releaseArtifactTaskPatterns = listOf(
    Regex("^assemble[A-Za-z0-9]*Release$"),
    Regex("^bundle[A-Za-z0-9]*Release$"),
    Regex("^package[A-Za-z0-9]*Release(?:Bundle|UniversalApk)?$"),
    Regex("^sign[A-Za-z0-9]*ReleaseBundle$"),
    Regex("^makeApkFromBundleFor[A-Za-z0-9]*Release$"),
    Regex("^extractApks(?:FromBundle)?For[A-Za-z0-9]*Release$"),
    Regex("^zipApksFor[A-Za-z0-9]*Release$"),
)
tasks.configureEach {
    if (releaseArtifactTaskPatterns.any { it.matches(name) }) {
        dependsOn(validateReleaseSigning)
    }
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
            // Bundled JetBrains Mono, for the player's timecodes and engine
            // strings. Compose's own resource pipeline rather than per-platform
            // asset loading, so commonMain can name the font directly.
            implementation("org.jetbrains.compose.components:components-resources:1.11.1")
            // System back, in common code: the player has to intercept it to
            // wind the engine down before its surface is taken away. Same
            // version as ui, and already on the graph via navigation-compose.
            implementation("org.jetbrains.compose.ui:ui-backhandler:1.11.1")
            implementation("org.jetbrains.androidx.navigation:navigation-compose:2.9.2")
            implementation("dev.chrisbanes.haze:haze:1.7.2")
            implementation("io.coil-kt.coil3:coil-compose:3.5.0")
            implementation("io.coil-kt.coil3:coil-network-ktor3:3.5.0")
            implementation("io.ktor:ktor-client-core:3.5.1")
            implementation("io.ktor:ktor-client-content-negotiation:3.5.1")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.1")
            implementation("com.squareup.okio:okio:3.17.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:3.5.1")
            implementation("com.squareup.okio:okio-fakefilesystem:3.17.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.5.1")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.9.3")
            implementation("androidx.browser:browser:1.8.0")
            implementation("androidx.core:core-ktx:1.13.1")
            implementation("androidx.work:work-runtime-ktx:2.10.1")
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
            implementation("androidx.test.ext:junit:1.3.0")
            implementation("androidx.test:runner:1.7.0")
            implementation("androidx.test.espresso:espresso-core:3.7.0")
        }
    }
}

// The generated accessor class is internal and lives beside the code that uses
// it, rather than defaulting to a package derived from the Android namespace.
compose.resources {
    publicResClass = false
    packageOfResClass = "moe.ditto.halo.resources"
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

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isDefault = true
        }
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // The two libmpv .so's under jni/ ship uncompressed already; nothing to strip.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

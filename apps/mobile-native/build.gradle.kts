plugins {
    kotlin("multiplatform") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    kotlin("plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    // The same composeApp module also builds the Android app. AGP 9.x is the
    // line that supports Gradle 9.x (wrapper is 9.5.0).
    id("com.android.application") version "9.3.0" apply false
}

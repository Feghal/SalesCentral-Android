// Standalone build of the SalesCentral Android SDK (JitPack / publishToMavenLocal /
// assembleRelease). Apps that vendor the :salescentral module (README "Option B") never
// evaluate this file — they supply AGP from their own root build instead.
//
// Toolchain (1.1.0): AGP 9.x with built-in Kotlin. AGP 9 compiles Kotlin itself, so the
// standalone `org.jetbrains.kotlin.android` plugin is no longer declared anywhere in this
// build (AGP 9 rejects it: "The 'org.jetbrains.kotlin.android' plugin is no longer required
// for Kotlin support since AGP 9.0"). AGP 9.4.0 itself only pulls Kotlin Gradle plugin
// 2.2.10 onto the classpath; the buildscript classpath below lifts that to the Kotlin
// version the SDK is actually built with — the mechanism Google documents for "Upgrade to a
// higher KGP version" under built-in Kotlin
// (developer.android.com/build/releases/past-releases/agp-9-0-0-release-notes).
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.library") version "9.4.0" apply false
}

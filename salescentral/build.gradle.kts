import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 built-in Kotlin: com.android.library alone compiles the Kotlin sources. The
    // standalone org.jetbrains.kotlin.android plugin this file used to apply is a hard error
    // under AGP 9 and is gone — see ../build.gradle.kts (standalone) or the consuming app's
    // root build for where AGP/Kotlin versions come from.
    id("com.android.library")
    // Publication for JitPack (https://jitpack.io) — consumers install the
    // public mirror as com.github.Feghal:SalesCentral-Android:<tag>.
    id("maven-publish")
}

android {
    namespace = "com.salescentral.sdk"
    // 37 (Android 17): the floor the current AndroidX release train declares in its AAR
    // metadata (core 1.19 / compose-ui 1.12 / navigation 2.10 all require compileSdk >= 37
    // and AGP >= 9.1). AGP 9 also makes consumers use the same-or-higher compileSdk by default.
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Replaces the removed `android.kotlinOptions { jvmTarget = "17" }` String DSL (a hard error
// since Kotlin 2.2: "Using 'jvmTarget: String' is an error. Please migrate to the
// compilerOptions DSL"). Under built-in Kotlin this `kotlin { }` block is the
// KotlinAndroidProjectExtension AGP registers itself; jvmTarget would default to
// compileOptions.targetCompatibility anyway, it is pinned here so the two can never drift.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Play Billing Library 9 — Google Play's floor for new apps and updates has been
    // version 8+ since 2026-08-31 (extension to 2026-11-01); 9.x is supported until
    // 2028-08-31. See PlayBillingConnector for the 7.x/8.x API migrations this required.
    implementation("com.android.billingclient:billing-ktx:9.1.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    // Device attestation (PlayIntegrityAttestService) — server-verified via
    // Google's Play Integrity API.
    implementation("com.google.android.play:integrity:1.6.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // Real org.json for JVM unit tests (android.jar only ships stubs).
    testImplementation("org.json:json:20260814")
}

// JitPack rewrites the coordinates to com.github.<user>:<repo>:<tag>; these
// values only matter for local `publishToMavenLocal` testing.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.salescentral"
                artifactId = "salescentral"
                version = "1.1.0"
            }
        }
    }
}

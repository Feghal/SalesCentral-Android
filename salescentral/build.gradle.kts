plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Publication for JitPack (https://jitpack.io) — consumers install the
    // public mirror as com.github.Feghal:SalesCentral-Android:<tag>.
    id("maven-publish")
}

android {
    namespace = "com.salescentral.sdk"
    compileSdk = 34

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
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.android.billingclient:billing-ktx:6.2.1")
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
    // Device attestation (PlayIntegrityAttestService) — server-verified via
    // Google's Play Integrity API.
    implementation("com.google.android.play:integrity:1.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // Real org.json for JVM unit tests (android.jar only ships stubs).
    testImplementation("org.json:json:20240303")
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
                version = "1.0.0"
            }
        }
    }
}

// Local manual-QA fixture: a real Android + Compose + Roborazzi project used to exercise GoldenDiff's
// new/edited/failed golden comparisons end-to-end. Not part of the plugin or desktop-app build graph.
plugins {
    // AGP 9+ compiles Kotlin sources itself; the old org.jetbrains.kotlin.android plugin is neither
    // needed nor allowed here.
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "com.github.dkwasniak.goldendiff.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.github.dkwasniak.goldendiff.sample"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    testImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.74.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.74.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

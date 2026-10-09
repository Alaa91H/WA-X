plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.wax.module.modern"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // This module compiles the modern runtime but is NEVER packaged in the legacy app.
    // The modern loader needs an explicitly selected future variant and signed-APK gate.
    compileOnly(libs.libxposed.modern.api)
    compileOnly(files("../app/libs/dexkit-android.aar"))
    testImplementation(libs.junit)
}

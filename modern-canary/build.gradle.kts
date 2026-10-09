plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.wax.module.modern.canary"
    compileSdk = 37

    defaultConfig {
        // Different application ID from the main WA X app: install/uninstall without data loss.
        applicationId = "com.wax.module.modern.canary"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-api102-canary"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // Unlike the legacy APK, the modern Xposed entry metadata MUST remain in APK root.
            excludes -= setOf("META-INF/xposed/java_init.list", "META-INF/xposed/module.prop", "META-INF/xposed/scope.list")
        }
    }
}

dependencies {
    implementation(project(":modern-runtime"))
    implementation(files("../app/libs/dexkit-android.aar"))
    // DexKit's local AAR does not declare its required FlatBuffers runtime.
    implementation(libs.flatbuffers)
    // API is furnished by Vector/LSPosed in the hooked target (never bundled in the APK).
    compileOnly(libs.libxposed.modern.api)
    implementation(libs.libxposed.modern.service)
}

import com.diffplug.spotless.LineEnding
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kspPlugin)
    alias(libs.plugins.compose)
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
}

val gitHash: String =
    providers
        .exec {
            commandLine("git", "rev-parse", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim().uppercase(Locale.getDefault()).take(8) }
        .getOrElse("UNKNOWN")

val baseVersionName = providers.gradleProperty("waxVersionName").get()
val baseVersionCode = providers.gradleProperty("waxVersionCode").get().toInt()
val releaseTag = providers.gradleProperty("releaseTag").orNull
val releaseVersion = releaseTag?.removePrefix("v")
val debugPackageName = providers.gradleProperty("debug_package_name")

if (releaseTag != null && releaseTag != "v$baseVersionName") {
    throw GradleException("Release tag $releaseTag does not match configured version v$baseVersionName")
}

val resolvedVersionName = releaseVersion ?: "$baseVersionName-dev+$gitHash"

android {
    namespace = "com.wax.module"
    //noinspection GradleDependency
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    // No product flavors. WA X ships as one APK that hooks both com.whatsapp and
    // com.whatsapp.w4b, and keeps their settings apart through the target-aware
    // settings model rather than through two application ids and two accidental
    // preference files. Removing the flavors before that model existed would have
    // merged every user's WhatsApp and Business settings into one, which is why the
    // settings work landed first and this landed second.

    defaultConfig {
        applicationId = "com.wax.module"
        minSdk = 28
        //noinspection OldTargetApi
        targetSdk = 34
        versionCode = baseVersionCode
        versionName = resolvedVersionName
        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        signingConfigs.create("config") {
            val androidStoreFile = project.findProperty("androidStoreFile") as String?
            if (!androidStoreFile.isNullOrEmpty()) {
                storeFile = rootProject.file(androidStoreFile)
                storePassword = project.property("androidStorePassword") as String
                keyAlias = project.property("androidKeyAlias") as String
                keyPassword = project.property("androidKeyPassword") as String
            }
        }

        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
        }

        buildConfigField("Boolean", "RESET_ON_INSTALL", "true")
    }

    packaging {
        resources {
            excludes += "META-INF/**"
            excludes += "okhttp3/**"
            excludes += "kotlin/**"
            excludes += "org/**"
            excludes += "**.properties"
            excludes += "**.bin"
        }

        jniLibs {
            useLegacyPackaging = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {

        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
            isMinifyEnabled = project.hasProperty("minify") && project.findProperty("minify").toString().toBoolean()
            //noinspection NotShrinkingResources
            isShrinkResources = false
            signingConfig =
                if (signingConfigs["config"].storeFile != null) signingConfigs["config"] else signingConfigs["debug"]
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        release {
            isMinifyEnabled = true
            //noinspection NotShrinkingResources
            isShrinkResources = false
            signingConfig =
                if (signingConfigs["config"].storeFile != null) signingConfigs["config"] else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    bundle {
        language {
            enableSplit = false
        }
    }

    buildFeatures {
        // Compose and view binding coexist: the Compose screens are new surfaces, and
        // converting a preference-fragment screen to Compose while it still has to keep
        // working is not a change worth making in the same commit as the foundation.
        compose = true
        viewBinding = true
        buildConfig = true
        aidl = true
        resValues = true
    }

    testCoverage {
        jacocoVersion = "0.8.15"
    }

    lint {
        disable += "SelectedPhotoAccess"
        // Kotlin 2.4.20 is fully supported through Gradle 9.7.0. Lint's generic
        // version suggestion currently asks for Gradle 9.8.0, which is newer but
        // outside Kotlin's fully supported range; keep the compatibility pin explicit.
        disable += "AndroidGradlePluginVersion"
        // Fires inside org.bouncycastle:bcpkix, which the backup signer depends on, and
        // reports an empty checkServerTrusted in the library's own code. WA X implements
        // no trust manager, so there is nothing to fix here and the finding cannot be
        // resolved by any change to this repository.
        disable += "TrustAllX509TrustManager"
        warning += "MissingTranslation"
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = false
        // Named explicitly because the Android Gradle Plugin no longer discovers
        // lint-baseline.xml on its own. Left implicit it is simply not applied: the file
        // kept looking authoritative to tools/baseline/check_baseline.py while lint ignored
        // it entirely, so the "baseline must not grow" ratchet was measuring a document
        // that had no effect on the build. Four entries, all VectorPath on artwork this
        // change does not own; every other finding is fixed rather than baselined.
        baseline = file("lint-baseline.xml")
    }

    // Static analysis is fail-closed: there is no baseline and even Info-severity
    // findings fail CI. The configuration only disables rules that are structurally
    // inappropriate for Android/Xposed code, never individual findings.
    detekt {
        buildUponDefaultConfig = true
        allRules = false
        ignoreFailures = false
        failOnSeverity = dev.detekt.gradle.extensions.FailOnSeverity.Info
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    }

    // T09: formatting. Scoped to the files this plan created rather than the whole
    // repository, because reformatting 171 pre-existing files in one change would bury
    // the real diff. Widen the target as each area is touched.
    //
    // Line endings are fixed by .gitattributes and pinned again here, so a Windows
    // checkout and a Linux runner hand ktlint the same bytes and a formatting gate
    // cannot pass locally and fail in CI.
    spotless {
        kotlin {
            // Whole-tree coverage, package structure deliberately not enumerated: an
            // enumerated list is a promise to update it on every move, and the package
            // migration silently emptied this one instead. `**/*.kt` over src/main
            // plus src/test cannot rot that way.
            target(
                "src/main/java/**/*.kt",
                "src/test/**/*.kt",
            )
            ktlint("1.8.0")
            // Pinned, not inherited from .gitattributes: with the endings left to the
            // checkout, a CRLF working tree makes ktlint 1.5.0 demand a different wrap
            // for a multi-line boolean expression than an LF one does, so the same commit
            // fails on one platform and passes on another. Pinning the expected bytes
            // makes the gate platform-independent.
            lineEndings = LineEnding.UNIX
        }
        kotlinGradle {
            target("*.kts")
            ktlint("1.8.0")
            lineEndings = LineEnding.UNIX
        }
    }
}

androidComponents {
    onVariants { variant ->
        // One output name for one APK. The old flavors produced a second file with a
        // `-Business` suffix for the same code, which only existed to give the two
        // builds separate preference files; the settings model does that now.
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { "WA-X-$it.apk" })
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    implementation(libs.colorpicker)
    implementation(files("libs/dexkit-android.aar"))
    implementation(libs.flatbuffers)
    compileOnly(libs.libxposed.legacy)
    // M06 preparation only: no modern entry point until all hooks and preferences migrate.
    // compileOnly prevents both APIs being shipped into the APK; the legacy loader stays active.
    compileOnly(libs.libxposed.modern.api)
    ksp(libs.androidx.room.compiler)

    implementation(libs.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.room.runtime)
    implementation(libs.rikkax.appcompat)
    implementation(libs.rikkax.core)
    implementation(libs.material)
    implementation(libs.rikkax.material)
    implementation(libs.rikkax.material.preference)
    implementation(libs.rikkax.widget.borderview)
    implementation(libs.jstyleparser)
    implementation(libs.okhttp)
    implementation(libs.filepicker)
    implementation(libs.betterypermissionhelper)
    implementation(libs.bcpkix.jdk18on)
    implementation(libs.arscblamer)
    implementation(libs.markwon.core)
    implementation(libs.remote.preferences)
}

configurations.all {
    exclude("androidx.appcompat", "appcompat")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk7")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk8")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Test>().configureEach {
    if (providers.gradleProperty("strictCollectAll").isPresent) {
        // Strict CI parses the XML results and emits one final verdict after every
        // independent gate has run. Keep producing coverage even when a test fails.
        ignoreFailures = true
    }
}

tasks.configureEach {
    if (name.endsWith("ReleaseArtProfile")) {
        enabled = false
    }
}

interface InjectedExecOps {
    @get:Inject val execOps: ExecOperations
}

afterEvaluate {
    // One install task now that there is one flavor. The restart helper below is
    // generic: it reads `debug_package_name`, which is set per install target.
    listOf("installDebug").forEach { taskName ->
        tasks.findByName(taskName)?.doLast {
            val packageName = debugPackageName.orNull
            if (!packageName.isNullOrBlank()) {
                runCatching {
                    val injected = project.objects.newInstance<InjectedExecOps>()
                    runBlocking {
                        delay(1000.milliseconds)
                        injected.execOps.exec {
                            commandLine(
                                "adb",
                                "shell",
                                "am",
                                "force-stop",
                                packageName,
                            )
                        }
                        delay(3000.milliseconds)
                        injected.execOps.exec {
                            commandLine(
                                "adb",
                                "shell",
                                "monkey",
                                "-p",
                                packageName,
                                "-c",
                                "android.intent.category.LAUNCHER",
                                "1",
                            )
                        }
                    }
                }
            }
        }
    }
}

// The Android Gradle plugin asks the Compose plugin for `compose-group-mapping` at the
// plugin version it detects, and for this combination that is a version that was never
// published, so the release variant failed to resolve it while debug built fine. The
// mapping only matters when publishing a Compose Multiplatform library, which this is
// not, so it is redirected to the published version matching our Kotlin. Scoped to that
// one configuration so nothing else in the build can be affected by it.
configurations.matching { it.name.contains("composeMappingProducer") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name == "compose-group-mapping") {
            useVersion(libs.versions.kotlin.get())
            because("the requested version was never published, and the mapping is unused for an app")
        }
    }
}

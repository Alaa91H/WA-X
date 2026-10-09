// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// T09 note: detekt and spotless are applied in :app only, not here. They stay in the one
// module that uses them so the root buildscript classpath is untouched.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
}

// Generic Java/Kotlin analyzers such as CodeQL probe for the conventional
// testClasses lifecycle task. Android does not create one at the root, so expose a
// deterministic compile-only alias. Test failures belong to the dedicated test gate and
// must not prevent CodeQL from extracting the production bytecode it needs to analyse.
tasks.register("testClasses") {
    group = "verification"
    description = "Compile debug production bytecode for generic analyzers such as CodeQL."
    dependsOn(":app:compileDebugKotlin", ":app:compileDebugJavaWithJavac")
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// T09 note: detekt and spotless are applied in :app only, not here. They stay in the one
// module that uses them so the root buildscript classpath is untouched.
plugins {
    alias(libs.plugins.androidApplication) apply false
}

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
        maven("https://jitpack.io")
    }
}

rootProject.name = "WA-X"
include(":app")
include(":modern-runtime")
// Debug-only modern API102 Xposed module. Never join the legacy release module graph by default.
if (providers.gradleProperty("enableModernCanary").orNull == "true") {
    include(":modern-canary")
}

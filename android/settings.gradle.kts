pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        // Google's Maven Central mirror first: fewer 429s from repo.maven.apache.org in CI.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's Maven Central mirror first: fewer 429s from repo.maven.apache.org in CI.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "canopy"

include(":app")
include(":core:model", ":core:domain", ":core:network", ":core:engine-bridge")
include(":core:data", ":core:designsystem", ":core:extensions", ":core:testing")
include(":feature:onboarding", ":feature:dashboard", ":feature:accounts")
include(":feature:transactions", ":feature:budget", ":feature:settings")

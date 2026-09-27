dependencyResolutionManagement {
    repositories {
        google()
        // Google's Maven Central mirror first: fewer 429s from repo.maven.apache.org in CI.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
rootProject.name = "build-logic"

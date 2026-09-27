// Pure Kotlin module with @Inject constructors: generate Dagger factories here so
// Hilt in the app can use them.
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    "implementation"(libs.lib("dagger"))
    "ksp"(libs.lib("dagger-compiler"))
}

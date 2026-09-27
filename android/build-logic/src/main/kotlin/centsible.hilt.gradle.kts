plugins {
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

dependencies {
    "implementation"(libs.lib("hilt-android"))
    "ksp"(libs.lib("hilt-compiler"))
}

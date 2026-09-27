plugins {
    id("centsible.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    buildFeatures {
        compose = true
    }
}

dependencies {
    val bom = platform(libs.lib("compose-bom"))
    "implementation"(bom)
    "implementation"(libs.lib("compose-ui"))
    "implementation"(libs.lib("compose-material3"))
    "implementation"(libs.lib("compose-material-icons"))
    "implementation"(libs.lib("compose-ui-tooling-preview"))
    "debugImplementation"(libs.lib("compose-ui-tooling"))
}

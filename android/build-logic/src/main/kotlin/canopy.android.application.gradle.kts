plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("canopy.hilt")
}

android {
    compileSdk = AppConfig.COMPILE_SDK
    defaultConfig {
        minSdk = AppConfig.MIN_SDK
        targetSdk = AppConfig.TARGET_SDK
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
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

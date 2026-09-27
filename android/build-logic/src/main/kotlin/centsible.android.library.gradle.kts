plugins {
    id("com.android.library")
}

android {
    compileSdk = AppConfig.COMPILE_SDK
    defaultConfig {
        minSdk = AppConfig.MIN_SDK
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    "testImplementation"(libs.lib("junit"))
    "testImplementation"(libs.lib("kotlinx-coroutines-test"))
}

// Hilt generates unit-test sources even in modules without tests; don't fail on those.
tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests.set(false)
}

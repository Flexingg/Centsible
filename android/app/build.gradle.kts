plugins {
    id("centsible.android.application")
}

android {
    namespace = "app.centsible"
    defaultConfig {
        applicationId = "app.centsible.budget"
        // CI passes the run number and tag; local builds stay at 1 / 0.2.0-dev.
        versionCode = providers.gradleProperty("centsible.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("centsible.versionName").orNull ?: "0.2.0-dev"
    }
    signingConfigs {
        // Release key comes from the environment (CI secrets). Every build must use the
        // same key, or Android refuses to install an update over the previous one.
        val keystore = System.getenv("CENTSIBLE_KEYSTORE")
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("CENTSIBLE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("CENTSIBLE_KEY_ALIAS")
            keyPassword = System.getenv("CENTSIBLE_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            // R8 stays off until a release build has been exercised on a device;
            // a stripped class would only show up as a crash at runtime.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:extensions"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:accounts"))
    implementation(project(":feature:transactions"))
    implementation(project(":feature:budget"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:planning"))
    implementation(project(":feature:reports"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.work) // daily bill reminders
}

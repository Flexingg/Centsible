plugins {
    id("canopy.android.application")
}

android {
    namespace = "app.canopy"
    defaultConfig {
        applicationId = "app.canopy.budget"
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)
}

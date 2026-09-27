plugins {
    id("canopy.android.feature")
}

android {
    namespace = "app.canopy.feature.accounts"
}

dependencies {
    implementation(libs.androidx.activity.compose) // system file picker for statement import
}

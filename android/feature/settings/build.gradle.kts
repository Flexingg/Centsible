plugins {
    id("canopy.android.feature")
}

android {
    namespace = "app.canopy.feature.settings"
}

dependencies {
    implementation(libs.zxing.core)
}

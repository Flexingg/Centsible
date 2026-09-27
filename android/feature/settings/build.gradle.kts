plugins {
    id("centsible.android.feature")
}

android {
    namespace = "app.centsible.feature.settings"
}

dependencies {
    implementation(libs.zxing.core)
}

plugins {
    id("centsible.android.feature")
}

android {
    namespace = "app.centsible.feature.accounts"
}

dependencies {
    implementation(libs.androidx.activity.compose) // system file picker for statement import
}

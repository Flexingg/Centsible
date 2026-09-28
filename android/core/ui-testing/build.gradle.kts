plugins {
    id("centsible.android.compose")
}

android {
    namespace = "app.centsible.core.uitesting"
}

// Shared by the feature modules' Robolectric tests (testImplementation).
dependencies {
    api(libs.compose.ui.test.junit4)
}

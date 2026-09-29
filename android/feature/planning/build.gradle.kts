plugins {
    id("centsible.android.feature")
}

android {
    namespace = "app.centsible.feature.planning"
}

dependencies {
    // Rule options (formulas, templates, split indexes) are JSON.
    implementation(libs.kotlinx.serialization.json)
}

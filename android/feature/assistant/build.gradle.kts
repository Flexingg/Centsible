plugins {
    id("centsible.android.feature")
}

android {
    namespace = "app.centsible.feature.assistant"
}

dependencies {
    implementation(project(":core:assistant"))
}

plugins {
    id("centsible.android.compose")
    id("centsible.hilt")
}

android {
    namespace = "app.centsible.core.extensions"
}

dependencies {
    api(project(":core:model"))
}

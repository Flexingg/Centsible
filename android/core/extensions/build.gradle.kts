plugins {
    id("canopy.android.compose")
    id("canopy.hilt")
}

android {
    namespace = "app.canopy.core.extensions"
}

dependencies {
    api(project(":core:model"))
}

plugins {
    id("canopy.android.compose")
}

android {
    namespace = "app.canopy.core.designsystem"
}

dependencies {
    api(project(":core:model"))
}

plugins {
    id("centsible.android.compose")
}

android {
    namespace = "app.centsible.core.designsystem"
}

dependencies {
    api(project(":core:model"))
}

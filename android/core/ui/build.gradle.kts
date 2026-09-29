// Shared screens pieces that need data (not just drawing): the category picker that can
// create and rename categories wherever one is chosen. Features use it; it uses domain.
plugins {
    id("centsible.android.compose")
    id("centsible.hilt")
}

android {
    namespace = "app.centsible.core.ui"
}

dependencies {
    api(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
}

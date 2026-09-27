plugins {
    id("centsible.jvm.library")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)
}

plugins {
    id("centsible.jvm.library")
    id("centsible.jvm.inject")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(project(":core:testing"))
    testImplementation(libs.turbine)
}

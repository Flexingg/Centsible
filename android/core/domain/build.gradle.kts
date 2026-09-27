plugins {
    id("canopy.jvm.library")
    id("canopy.jvm.inject")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(project(":core:testing"))
    testImplementation(libs.turbine)
}

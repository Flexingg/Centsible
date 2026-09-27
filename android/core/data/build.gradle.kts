plugins {
    id("canopy.android.library")
    id("canopy.hilt")
    id("canopy.kotlin.serialization")
}

android {
    namespace = "app.canopy.core.data"
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:network"))
    implementation(project(":core:engine-bridge"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}

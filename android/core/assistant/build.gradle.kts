// The financial assistant: Claude, called with the person's own API key, with tools over
// everything the app can read (and, with permission and approval, change).
plugins {
    id("centsible.jvm.library")
    id("centsible.jvm.inject")
    id("centsible.kotlin.serialization")
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.anthropic.java)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":core:testing"))
    testImplementation(libs.okhttp.mockwebserver)
}

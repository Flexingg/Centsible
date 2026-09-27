plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    "implementation"(libs.lib("kotlinx-serialization-json"))
}

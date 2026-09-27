// Pure Kotlin module: no Android types allowed (keeps model/domain KMP-ready).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    "testImplementation"(libs.lib("junit"))
    "testImplementation"(libs.lib("kotlinx-coroutines-test"))
}

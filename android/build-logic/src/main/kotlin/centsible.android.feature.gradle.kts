// A feature module: screens + ViewModels. Features depend on domain and the design
// system only, never on each other or on network/engine code.
plugins {
    id("centsible.android.compose")
    id("centsible.hilt")
    id("io.github.takahirom.roborazzi")
}

dependencies {
    "implementation"(project(":core:model"))
    "implementation"(project(":core:domain"))
    "implementation"(project(":core:designsystem"))
    "implementation"(project(":core:extensions"))
    "implementation"(libs.lib("hilt-navigation-compose"))
    "implementation"(libs.lib("androidx-lifecycle-runtime-compose"))
    "implementation"(libs.lib("androidx-lifecycle-viewmodel-compose"))
    "implementation"(libs.lib("kotlinx-coroutines-android"))

    "testImplementation"(libs.lib("robolectric"))
    "testImplementation"(libs.lib("roborazzi"))
    "testImplementation"(libs.lib("roborazzi-compose"))
    "testImplementation"(platform(libs.lib("compose-bom")))
    "testImplementation"(libs.lib("compose-ui-test-junit4"))
    "debugImplementation"(libs.lib("compose-ui-test-manifest"))
    "testImplementation"(project(":core:testing"))
}

// Robolectric normally downloads its Android runtime at test time, which fails behind
// proxies and in locked-down CI. Resolve it through Gradle (cached, mirrored) instead.
val robolectricRuntime = configurations.create("robolectricRuntime") { isTransitive = false }
dependencies {
    // SDK 35 runtime used by the screenshot tests' @Config(sdk = [35]); matches Robolectric 4.17.
    robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
}
val robolectricDir = layout.buildDirectory.dir("robolectric-runtime")
val syncRobolectricRuntime = tasks.register<Sync>("syncRobolectricRuntime") {
    from(robolectricRuntime)
    into(robolectricDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(syncRobolectricRuntime)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDir.get().asFile.absolutePath)
}

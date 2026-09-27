plugins {
    id("centsible.jvm.library")
    id("centsible.kotlin.serialization")
}

dependencies {
    api(project(":core:domain"))
    api(libs.ktor.client.core)
    api(libs.kotlinx.serialization.json) // BridgeApi takes JsonObject patches
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.snakeyaml) // reads contract/openapi.yaml for request checks
    testImplementation(project(":core:testing"))
}

// Decoder tests read the fixtures the bridge's contract suite records from a real Actual.
tasks.test {
    systemProperty("contract.fixtures", rootProject.file("../contract/fixtures").absolutePath)
    systemProperty("contract.openapi", rootProject.file("../contract/openapi.yaml").absolutePath)
}

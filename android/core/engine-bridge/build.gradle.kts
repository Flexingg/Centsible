plugins {
    id("centsible.jvm.library")
    id("centsible.jvm.inject")
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:network"))
    testImplementation(project(":core:testing"))
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.okhttp) // EndToEndTest talks to a real bridge over HTTP
}

tasks.test {
    systemProperty("contract.fixtures", rootProject.file("../contract/fixtures").absolutePath)
    // Set by CI (or by hand) after starting bridge/test/support/e2e-server.ts.
    System.getenv("E2E_PAIRING_URI")?.let { systemProperty("e2e.pairingUri", it) }
    System.getenv("E2E_SIMPLEFIN_TOKEN")?.let { systemProperty("e2e.simplefinToken", it) }
    System.getenv("E2E_SETUP_URL")?.let { systemProperty("e2e.setupUrl", it) }
    System.getenv("E2E_SETUP_CODE")?.let { systemProperty("e2e.setupCode", it) }
}

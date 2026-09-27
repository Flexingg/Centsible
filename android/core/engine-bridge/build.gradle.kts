plugins {
    id("centsible.jvm.library")
    id("centsible.jvm.inject")
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:network"))
    testImplementation(project(":core:testing"))
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    systemProperty("contract.fixtures", rootProject.file("../contract/fixtures").absolutePath)
}

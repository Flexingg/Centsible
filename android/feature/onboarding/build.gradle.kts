plugins {
    id("centsible.android.feature")
}

android {
    namespace = "app.centsible.feature.onboarding"
}

dependencies {
    implementation(libs.play.services.code.scanner)
}

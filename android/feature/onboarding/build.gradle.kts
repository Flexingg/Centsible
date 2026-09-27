plugins {
    id("canopy.android.feature")
}

android {
    namespace = "app.canopy.feature.onboarding"
}

dependencies {
    implementation(libs.play.services.code.scanner)
}

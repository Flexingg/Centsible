plugins {
    id("centsible.android.feature")
}

android {
    namespace = "app.centsible.feature.settings"
}

dependencies {
    implementation(libs.zxing.core)
    // Plaid Link: Plaid's own bank sign-in screens (Plaid replaced Teller in 2026).
    implementation(libs.plaid.link)
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "network.orion.feature.device"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    buildFeatures { compose = true }
    defaultConfig { minSdk = libs.versions.min.sdk.get().toInt() }
}
dependencies {
    implementation(project(":core:queries"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.coroutines.android)
}

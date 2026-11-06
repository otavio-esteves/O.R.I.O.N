plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "network.orion.skills.notifications"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    defaultConfig { minSdk = libs.versions.min.sdk.get().toInt() }
}

dependencies {
    api(project(":skills:api"))
    implementation(project(":core:commands"))
    testImplementation(libs.junit4)
}

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "network.orion.skills.timers"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    defaultConfig { minSdk = libs.versions.min.sdk.get().toInt() }
}

dependencies {
    api(project(":skills:api"))
    implementation(project(":core:commands"))
    implementation(project(":data:timers"))
    testImplementation(libs.junit4)
}

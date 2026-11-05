plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "network.orion.core.policy"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}

dependencies {
    api(project(":core:commands"))
    api(project(":core:common"))
    api(project(":core:model"))
    implementation(project(":core:skills:metadata"))

    testImplementation(libs.junit4)
    testImplementation(project(":core:skills:metadata"))
}

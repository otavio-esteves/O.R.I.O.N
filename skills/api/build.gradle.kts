plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:skills:metadata"))
}

android {
    namespace = "network.orion.skills.api"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}

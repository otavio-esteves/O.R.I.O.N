plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    api(project(":core:actions"))
    api(project(":core:security"))
    api(project(":core:time"))
    api(project(":data:database"))
}

android {
    namespace = "network.orion.data.actions"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}

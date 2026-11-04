plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    api(project(":data:database"))
    api(project(":core:security"))
    implementation(project(":core:time"))
}

android {
    namespace = "network.orion.data.tasks"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}

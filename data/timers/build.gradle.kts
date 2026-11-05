plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    implementation(project(":core:queries"))
    api(project(":data:database"))
    api(project(":core:security"))
    implementation(project(":core:time"))
    implementation(project(":core:commands"))
    implementation(project(":data:actions"))
}

android {
    namespace = "network.orion.data.timers"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}

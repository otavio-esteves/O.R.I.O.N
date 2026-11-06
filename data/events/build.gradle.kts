plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    implementation(project(":core:queries"))
    api(project(":data:database"))
    implementation(project(":core:time"))
    testImplementation(libs.junit4)
}

android {
    namespace = "network.orion.data.events"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}

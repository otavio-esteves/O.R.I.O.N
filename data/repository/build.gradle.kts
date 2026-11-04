plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "network.orion.data.repository"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}

dependencies {
    implementation(project(":core:common"))
    api(project(":core:ingress"))
    implementation(project(":core:security"))
    implementation(project(":core:time"))
    implementation(project(":data:database"))
}

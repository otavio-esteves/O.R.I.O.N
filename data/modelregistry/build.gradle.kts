plugins { alias(libs.plugins.android.library) }

android {
    namespace = "network.orion.data.modelregistry"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    defaultConfig { minSdk = libs.versions.min.sdk.get().toInt() }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:time"))
    api(project(":data:database"))
    testImplementation(libs.junit4)
}

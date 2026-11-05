plugins { alias(libs.plugins.android.library) }
android {
    namespace = "network.orion.skills.device"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    defaultConfig { minSdk = libs.versions.min.sdk.get().toInt() }
}
dependencies {
    api(project(":core:queries"))
    api(project(":core:skills:metadata"))
    implementation(project(":core:time"))
    testImplementation(libs.junit4)
}

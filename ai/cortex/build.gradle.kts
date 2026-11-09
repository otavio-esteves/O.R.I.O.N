plugins { alias(libs.plugins.android.library) }
android {
    namespace = "network.orion.ai.cortex"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    defaultConfig { minSdk = libs.versions.min.sdk.get().toInt() }
}
dependencies {
    api(project(":ai:api"))
    testImplementation(libs.junit4)
}

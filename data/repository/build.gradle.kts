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
    testImplementation(libs.junit4)
}

// Explicit opt-in fixture verification: a requested missing/invalid artifact fails.
val modelFixture = providers.gradleProperty("orionModelFixture")
tasks.withType<Test>().configureEach {
    if (modelFixture.isPresent) systemProperty("orionModelFixture", modelFixture.get())
    else exclude("**/OrionQwenGgufFixtureTest.class")
}

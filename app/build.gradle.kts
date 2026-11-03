plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "network.orion.app"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "network.orion"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug { ndk { abiFilters += setOf("arm64-v8a", "x86_64") } }
        release { ndk { abiFilters += "arm64-v8a" } }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:health"))
    implementation(project(":core:time"))
    implementation(project(":core:observability"))
    implementation(project(":core:recovery"))
    implementation(project(":core:ingress"))
    implementation(project(":core:actions"))
    implementation(project(":core:model"))
    implementation(project(":core:resources"))
    implementation(project(":core:exitinfo"))
    implementation(project(":data:database"))
    implementation(project(":data:datastore"))
    implementation(project(":feature:home"))
    implementation(project(":feature:diagnostics"))
    implementation(project(":feature:settings"))
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.android)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
}

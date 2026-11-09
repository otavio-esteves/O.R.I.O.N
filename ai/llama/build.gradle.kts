plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "network.orion.ai.llama"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                targets += "orion_llama"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
    buildTypes {
        release { ndk { debugSymbolLevel = "FULL" } }
    }
}

dependencies {
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(project(":core:resources"))
}

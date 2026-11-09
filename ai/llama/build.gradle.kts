plugins { alias(libs.plugins.android.library) }

android {
    namespace = "network.orion.ai.llama"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
                targets += "orion_llama"
            }
        }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = libs.versions.cmake.get() }
    }
}

dependencies {
    implementation(project(":ai:api"))
    testImplementation(libs.junit4)
}

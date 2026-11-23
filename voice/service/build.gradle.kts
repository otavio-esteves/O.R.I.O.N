import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

val neuralVoiceAar = providers.gradleProperty("orionNeuralVoiceAar").orNull?.let { rootProject.file(it) }
val neuralApiDir = layout.buildDirectory.dir("orionNeuralVoiceApi")
val neuralApi = neuralVoiceAar?.let { input ->
    tasks.register<Copy>("extractOrionNeuralVoiceApi") {
        from(zipTree(input)) { include("classes.jar") }
        into(neuralApiDir)
        doFirst {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(input.readBytes()).joinToString("") { "%02x".format(it) }
            check(hash == "e65fc8f60714f95649914bdab79d6c98a6bcd71c8417f12e17c44485fee3a610") {
                "Unexpected Sherpa-ONNX Android runtime SHA-256."
            }
        }
    }
}

android {
    namespace = "network.orion.voice.service"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
    buildTypes {
        debug { ndk { abiFilters += setOf("arm64-v8a", "x86_64") } }
        release { ndk { abiFilters += "arm64-v8a" } }
    }
}

androidComponents.onVariants { variant ->
    variant.sources.kotlin!!.addStaticSourceDirectory(if (neuralApi == null) "src/platform/kotlin" else "src/neural/kotlin")
}

dependencies {
    if (neuralApi != null) compileOnly(files(neuralApiDir.map { it.file("classes.jar") }).builtBy(neuralApi))
    api(project(":voice:ipc"))
    implementation(project(":core:time"))
    implementation(libs.coroutines.android)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

val neuralVoiceAar = providers.gradleProperty("orionNeuralVoiceAar").orNull?.let { rootProject.file(it) }
val previewBuildType = "s21CortexPreview"
val neuralApiDir = layout.buildDirectory.dir("orionNeuralVoiceApi")
val neuralApi = neuralVoiceAar?.let { input ->
    tasks.register<Copy>("extractOrionNeuralVoiceApi") {
        inputs.file(input).withPathSensitivity(PathSensitivity.NONE)
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
        if (neuralApi != null) {
            create(previewBuildType) {
                initWith(getByName("release"))
                matchingFallbacks += "release"
            }
        }
    }
}

androidComponents.onVariants { variant ->
    val ttsSource = if (variant.buildType == previewBuildType) "src/neural/kotlin" else "src/platform/kotlin"
    variant.sources.kotlin!!.addStaticSourceDirectory(ttsSource)
}

dependencies {
    if (neuralApi != null) {
        add("${previewBuildType}CompileOnly", files(neuralApiDir.map { it.file("classes.jar") }).builtBy(neuralApi))
    }
    api(project(":voice:ipc"))
    implementation(project(":core:time"))
    implementation(libs.androidx.core)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

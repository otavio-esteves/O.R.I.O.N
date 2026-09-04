plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.register<Exec>("toolchainInfo") {
    group = "verification"
    description = "Prints the versioned O.R.I.O.N. build toolchain."
    commandLine("sh", "scripts/toolchain-info.sh", gradle.gradleVersion)
}

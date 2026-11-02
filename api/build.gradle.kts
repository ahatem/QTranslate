import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

version = "2.2.0"  // only bump when the API interface changes

plugins {
    id("buildsrc.convention.kotlin-jvm")
    `maven-publish`
}

// This module is the contract third-party plugins compile against, so every visibility in it is a
// decision rather than a default. Strict mode makes the compiler ask for that decision explicitly
// and refuses to let a helper become part of the published surface by omission.
kotlin {
    explicitApi()
    // ABI validation guards the binary contract third-party plugins compile against. The committed
    // reference dump (api/api.api) is the baseline: checkKotlinAbi runs with `check` and fails on
    // any public-surface change, compatible ones included; updateKotlinAbi regenerates it after an
    // agreed API change.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()
}

dependencies {
    api(libs.kotlinxCoroutines)
    api(libs.bundles.result)
    testImplementation(kotlin("test"))
}

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
            groupId    = "com.github.ahatem"
            artifactId = "qtranslate-api"
            version    = project.version.toString()
        }
    }
}

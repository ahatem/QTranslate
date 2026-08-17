version = "2.0.0"  // only bump when the API interface changes

plugins {
    id("buildsrc.convention.kotlin-jvm")
    `maven-publish`
}

// This module is the contract third-party plugins compile against, so every visibility in it is a
// decision rather than a default. Strict mode makes the compiler ask for that decision explicitly
// and refuses to let a helper become part of the published surface by omission.
kotlin {
    explicitApi()
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

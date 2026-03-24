version = "1.0.0"  // only bump when the API interface changes

plugins {
    id("buildsrc.convention.kotlin-jvm")
    `maven-publish`
}

dependencies {
    api(libs.kotlinxCoroutines)
    api(libs.bundles.result)
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
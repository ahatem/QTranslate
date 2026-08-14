plugins {
    id("buildsrc.convention.kotlin-jvm")
    // Publishes a fake PluginContext for plugin tests. Every plugin needs one, and four
    // hand-written copies drifted apart as the API changed.
    `java-test-fixtures`
}

dependencies {
    api(project(":api"))

    implementation(libs.kotlinxSerialization)
    implementation(libs.bundles.ktor)

    testFixturesImplementation(libs.kotlinxCoroutines)
}
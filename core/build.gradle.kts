plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

repositories {
    google()
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation(kotlin("reflect"))

    implementation(project(":api"))

    implementation(libs.bundles.ktor)
    implementation(libs.kotlinxSerialization)
    implementation(libs.bundles.datastore)

    implementation(libs.kotlinxCoroutinesSwing)

}
plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    api(project(":api"))

    implementation(libs.kotlinxSerialization)
    implementation(libs.bundles.ktor)
}
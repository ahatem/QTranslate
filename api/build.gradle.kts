plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    api(libs.kotlinxCoroutines)
    api(libs.bundles.result)
}

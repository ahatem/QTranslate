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

    implementation(libs.poi.ooxml)
    implementation(libs.pdfbox)

    testImplementation(kotlin("test"))
    // Scripts responses so retry, proxy and timeout rules can be asserted without a live server.
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinxCoroutinesTest)

}

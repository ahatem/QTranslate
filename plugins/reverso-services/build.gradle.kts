plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":api"))
    implementation(project(":plugins:common"))
    implementation(libs.kotlinxSerialization)
    implementation(libs.jsoup)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutines)
}

tasks.shadowJar {
    archiveBaseName.set("reverso-services-plugin")
    archiveClassifier.set("")
    archiveVersion.set("")
}

plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":api"))
    implementation(project(":plugins:common"))

    implementation(libs.kotlinxSerialization)
}

tasks.shadowJar {
    archiveBaseName.set("ai-services-plugin")
    archiveClassifier.set("")
    archiveVersion.set("")
}
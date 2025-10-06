plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":api"))
    implementation(project(":plugins:common"))
    implementation(libs.kotlinxSerialization)

    implementation(libs.java.diff.utils)
}

tasks.shadowJar {
    archiveBaseName.set("bing-services-plugin")
    archiveClassifier.set("")
    archiveVersion.set("")
}

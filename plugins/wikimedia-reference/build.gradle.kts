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
    testImplementation(testFixtures(project(":plugins:common")))
}

tasks.shadowJar {
    archiveBaseName.set("wikimedia-reference-plugin")
    archiveClassifier.set("")
    archiveVersion.set("")
}

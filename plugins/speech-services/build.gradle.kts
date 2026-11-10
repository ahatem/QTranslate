plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":api"))
    implementation(project(":plugins:common"))
    implementation(libs.kotlinxSerialization)
    implementation(libs.vosk)
    implementation(libs.jna)

    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":plugins:common")))
    testImplementation(libs.kotlinxCoroutines)
}

tasks.shadowJar {
    archiveBaseName.set("speech-services-plugin")
    archiveClassifier.set("")
    archiveVersion.set("")
}

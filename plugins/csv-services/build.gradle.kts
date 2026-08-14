plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":api"))
    implementation(project(":plugins:common"))

    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":plugins:common")))
    testImplementation(libs.kotlinxCoroutines)
}

tasks.shadowJar {
    archiveBaseName.set("csv-services-plugin")
    archiveClassifier.set("")
    archiveVersion.set("")
}

plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":api"))
    implementation(project(":plugins:common"))

    testImplementation(kotlin("test"))
}

tasks.shadowJar {
    archiveBaseName.set("google-lens-plugin")
    archiveClassifier.set("")
    archiveVersion.set("")
}

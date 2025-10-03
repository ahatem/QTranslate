plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    application
}

repositories {
    mavenCentral()
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    maven("https://jitpack.io")
}

dependencies {
    testImplementation(kotlin("test"))

    // KotlinX ecosystem
    implementation(libs.bundles.kotlinxEcosystem)

    // JSON
    implementation(libs.gson)

    // Networking (Unirest with BOM)
    implementation(platform(libs.unirest.bom))
    implementation(libs.bundles.unirest)

    // Parsing
    implementation(libs.jsoup)

    // UI + Desktop
    implementation(libs.jintellitype)
    implementation(libs.jlayer)
    implementation(libs.miglayout)

    implementation(libs.bundles.flatlaf)
    implementation(libs.jsvg)

    implementation(libs.swt) { isTransitive = false }
}


application {
    mainClass = "com.github.ahatem.qtranslate.MainKt"
}

plugins {
    id("buildsrc.convention.kotlin-jvm")
}

repositories {
    mavenCentral()
    google()
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    maven("https://jitpack.io")
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))

    implementation(libs.kotlinxCoroutines)
    implementation(libs.kotlinxCoroutinesSwing)
    implementation(libs.kotlinxSerialization)

    implementation(libs.bundles.flatlaf)
    implementation(libs.jsvg)
    implementation(libs.miglayout)

    implementation(libs.jna)
    implementation(libs.qinput)

    implementation(libs.commonmark)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutinesTest)
    // Test-only: production code never references FlatInterFont from this module — AppUiSetup in
    // :app is the only runtime caller — but the font-resolution tests want a real bundled family
    // to prove toFont() loads it lazily.
    testImplementation(libs.flatlaf.fonts.inter)
}

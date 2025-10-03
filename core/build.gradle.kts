plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow")
}

group = "com.github.ahatem"
version = "1.1.0"

dependencies {
    testImplementation(kotlin("test"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    implementation("com.google.code.gson:gson:2.13.1")

    implementation(platform("com.konghq:unirest-java-bom:4.4.5"))
    implementation("com.konghq:unirest-java-core")
    implementation("com.konghq:unirest-modules-gson")

    implementation("org.jsoup:jsoup:1.20.1")

    implementation("com.melloware:jintellitype:1.5.6")
    implementation("com.github.umjammer:jlayer:1.0.3")

    implementation("com.miglayout:miglayout-swing:11.1")
    implementation("com.formdev:flatlaf:3.7-SNAPSHOT")
    implementation("com.formdev:flatlaf-intellij-themes:3.7-SNAPSHOT")
    implementation("com.formdev:flatlaf-extras:3.7-SNAPSHOT")
    implementation("com.github.weisj:jsvg:2.0.0")

    implementation("org.eclipse.platform:org.eclipse.swt.win32.win32.x86_64:3.123.0") { isTransitive = false }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(11)
}

application {
    mainClass.set("com.github.ahatem.qtranslate.MainKt")
}





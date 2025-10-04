plugins {
    id("buildsrc.convention.kotlin-jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    api(libs.kotlinxCoroutines)
    api(libs.result)
}

tasks.test {
    useJUnitPlatform()
}

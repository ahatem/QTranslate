plugins {
  kotlin("jvm") version "1.8.20"
  application
  id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "org.pnix"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
  maven { url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots") }
  maven { url = uri("https://jitpack.io") }

}

dependencies {
  testImplementation(kotlin("test"))

  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.0-RC")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.0-RC")

  implementation("com.google.code.gson:gson:2.10.1")
  implementation("com.konghq:unirest-java:3.14.1")
  implementation("org.jsoup:jsoup:1.16.1")
  implementation("com.neovisionaries:nv-i18n:1.29")

  implementation("net.java.dev.jna:jna-platform:5.13.0")
  implementation("com.melloware:jintellitype:1.4.1")
  implementation("com.github.umjammer:jlayer:1.0.2")

  implementation("com.miglayout:miglayout-swing:11.1")
  implementation("com.formdev:flatlaf:3.1.1")
  implementation("com.formdev:flatlaf-intellij-themes:3.1.1")
  implementation("com.formdev:flatlaf-extras:3.1.1")
  implementation("com.formdev:svgSalamander:1.1.4")
}

tasks.test {
  useJUnitPlatform()
}

kotlin {
  jvmToolchain(11)
}

application {
  mainClass.set("com.pnix.qtranslate.MainKt")
}





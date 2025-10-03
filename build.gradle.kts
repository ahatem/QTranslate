plugins {
    kotlin("jvm") version "2.2.20" apply false
    id("com.gradleup.shadow") version "9.2.2" apply false
}

allprojects {
    // This block applies to the root project AND all subprojects.
    repositories {
        mavenCentral()
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
        maven("https://jitpack.io")
        maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
    }
}


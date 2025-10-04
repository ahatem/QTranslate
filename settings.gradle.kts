dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}


plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include("core")

rootProject.name = "qtranslate-app"

include("api")
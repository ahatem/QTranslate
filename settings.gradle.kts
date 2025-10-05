dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include("api")
include("core")
include("plugins:common")
include("plugins:google-services")

rootProject.name = "qtranslate-app"

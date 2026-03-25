dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "qtranslate-app"

include("api")

include("core")
include("ui-swing")

include("app")

include("plugins:common")
include("plugins:google-services")
include("plugins:bing-services")
include("plugins:ai-services")
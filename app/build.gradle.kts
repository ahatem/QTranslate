plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
    alias(libs.plugins.shadow)
}

group = "com.github.ahatem"
version = System.getenv("APP_VERSION") ?: "dev"

application {
    mainClass.set("com.github.ahatem.qtranslate.app.MainKt")
}

repositories {
    mavenCentral()
    google()
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    maven("https://jitpack.io")
}

dependencies {
    // Modules
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":ui-swing"))

    // Coroutines — needed for runBlocking in Main.kt and AppScope
    implementation(libs.kotlinxCoroutines)

    // Serialization — used in buildDependencies for the shared Json instance
    implementation(libs.kotlinxSerialization)

    // Ktor — shared HttpClient created here and injected into Updater
    implementation(libs.bundles.ktor)

    // JLayer — MP3 decoding library for JLayerAudioPlayer
    implementation(libs.jlayer)

    // FlatLaf — referenced directly in AppUiSetup (FlatLaf, FontUtils).
    // Also a transitive dep via :ui-swing, but needed here for compilation.
    implementation(libs.bundles.flatlaf)

    // Logging — SLF4J API + Logback backend
    // SLF4J is the facade; Logback does the actual writing.
    // The :api module's Logger interface bridges to SLF4J here in :app.
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // Tests
    testImplementation(kotlin("test"))
}

tasks.shadowJar {
    // Output: QTranslate.jar — the name users will see and double-click
    archiveBaseName.set("QTranslate")
    archiveClassifier.set("")
    archiveVersion.set("")

    manifest {
        attributes["Main-Class"] = "com.github.ahatem.qtranslate.app.MainKt"
    }

    // Exclude duplicate META-INF files that Shadow picks up from dependencies
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
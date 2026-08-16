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

    // Bundled plugins are distributed as thin JARs. Keep their shared runtime
    // dependencies in the host while external plugins remain self-contained.
    runtimeOnly(project(":plugins:common"))
    runtimeOnly(libs.jsoup)
    runtimeOnly(libs.java.diff.utils)

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

val manualQaPluginProjects = rootProject.subprojects.filter {
    it.path.startsWith(":plugins:") && it.path != ":plugins:common"
}
val manualQaDirectory = layout.buildDirectory.dir("manual-qa")
val externalPluginsDirectory = providers.gradleProperty("pluginsDir")
    .orElse(rootProject.layout.projectDirectory.dir("plugins").asFile.absolutePath)

val prepareManualQaPlugins by tasks.registering(Sync::class) {
    group = "application"
    description = "Builds bundled plugins and stages them with external plugin JARs for manual QA."
    dependsOn(manualQaPluginProjects.map { "${it.path}:jar" })
    into(manualQaDirectory.map { it.dir("app-data/plugins") })

    manualQaPluginProjects.forEach { pluginProject ->
        // The name is read now, at configuration time, rather than inside rename. A lambda that
        // reads pluginProject.name runs during execution and so holds on to the Project itself,
        // which the configuration cache cannot serialize — that made this task fail the build
        // before it ever launched the app.
        val stagedName = "${pluginProject.name}-plugin.jar"
        from(pluginProject.tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
            rename { stagedName }
        }
    }
    from(externalPluginsDirectory) {
        include("*.jar")
    }
}

val prepareManualQaRuntime by tasks.registering(Delete::class) {
    dependsOn(prepareManualQaPlugins)
    // Always inspect freshly staged JARs while retaining the tester's other settings.
    delete(manualQaDirectory.map { it.file("app-data/datastore/plugin_registry.preferences_pb") })
}

tasks.register<JavaExec>("runWithPlugins") {
    group = "application"
    description = "Launches the full QTranslate UI with bundled and external plugins for manual QA."
    dependsOn(prepareManualQaRuntime)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    standardInput = System.`in`
    systemProperty("appData", manualQaDirectory.get().dir("app-data").asFile.absolutePath)
}

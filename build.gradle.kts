import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Zip

plugins {
    base
}

data class BundledPlugin(
    val projectPath: String,
    val thinArchiveFile: Provider<RegularFile>,
    val standaloneArchiveFile: Provider<RegularFile>,
    val id: String,
    val name: String,
    val version: String,
    val minApiVersion: String,
) {
    val releaseFileName = "$id-$version.jar"
    val bundledFileName = "${projectPath.substringAfterLast(':')}-plugin.jar"
}

val releaseVersion = providers.gradleProperty("releaseVersion")
    .orElse(providers.environmentVariable("APP_VERSION"))
    .orElse("dev")
val releaseOutputDirectory = layout.buildDirectory.dir("release")
evaluationDependsOn(":app")
val appProject = project(":app")
val appArchive = appProject.tasks.named<AbstractArchiveTask>("shadowJar")
val appArchiveFile = appProject.layout.buildDirectory.file("libs/QTranslate.jar")
val minimalPluginIds = setOf("google-services", "bing-services", "mozhi-services")

val bundledPlugins = subprojects
    .filter { it.path.startsWith(":plugins:") && it.name != "common" }
    .map { pluginProject ->
        evaluationDependsOn(pluginProject.path)
        val manifestFile = pluginProject.file("src/main/resources/plugin.json")
        require(manifestFile.isFile) { "Plugin ${pluginProject.path} is missing plugin.json" }
        @Suppress("UNCHECKED_CAST")
        val manifest = JsonSlurper().parse(manifestFile) as Map<String, Any?>
        BundledPlugin(
            projectPath = pluginProject.path,
            thinArchiveFile = pluginProject.tasks.named<AbstractArchiveTask>("jar").flatMap { it.archiveFile },
            standaloneArchiveFile = pluginProject.tasks.named<AbstractArchiveTask>("shadowJar").flatMap { it.archiveFile },
            id = requireNotNull(manifest["id"] as? String) { "Plugin ${pluginProject.path} has no id" },
            name = requireNotNull(manifest["name"] as? String) { "Plugin ${pluginProject.path} has no name" },
            version = requireNotNull(manifest["version"] as? String) { "Plugin ${pluginProject.path} has no version" },
            minApiVersion = requireNotNull(manifest["minApiVersion"] as? String) {
                "Plugin ${pluginProject.path} has no minApiVersion"
            },
        )
    }
    .sortedBy { it.id }

val smokeDirectory = layout.buildDirectory.dir("plugin-smoke-test")
val cleanPluginSmoke by tasks.registering(Delete::class) {
    delete(smokeDirectory)
}
val preparePluginSmoke by tasks.registering(Sync::class) {
    dependsOn(cleanPluginSmoke)
    dependsOn(bundledPlugins.map { "${it.projectPath}:jar" })
    into(smokeDirectory.map { it.dir("app-data/plugins") })
    bundledPlugins.forEach { plugin ->
        from(plugin.thinArchiveFile) {
            rename { plugin.bundledFileName }
        }
    }
}

tasks.register<JavaExec>("smokeTestAllPlugins") {
    group = "verification"
    description = "Loads, configures, toggles, and exercises every bundled plugin."
    dependsOn(preparePluginSmoke, ":app:classes")
    classpath(
        appProject.layout.buildDirectory.dir("classes/kotlin/main"),
        appProject.layout.buildDirectory.dir("resources/main"),
        appProject.configurations.named("runtimeClasspath")
    )
    mainClass.set("com.github.ahatem.qtranslate.app.PluginSmokeTestMainKt")
    val root = smokeDirectory.get().asFile
    args(root.resolve("app-data").absolutePath, root.resolve("report.txt").absolutePath)
    systemProperty("java.awt.headless", "true")
}

fun Zip.configureBundle(plugins: List<BundledPlugin>, variant: String) {
    group = "distribution"
    description = "Builds the $variant QTranslate distribution."
    dependsOn(appArchive)
    dependsOn(plugins.map { "${it.projectPath}:jar" })
    destinationDirectory.set(releaseOutputDirectory)
    archiveFileName.set("QTranslate-${variant.replaceFirstChar(Char::uppercase)}-${releaseVersion.get()}.zip")

    into("QTranslate") {
        from(appArchiveFile) {
            rename { "QTranslate.jar" }
        }
        from(rootProject.file("languages")) {
            into("languages")
        }
        from(rootProject.file("themes")) {
            into("themes")
        }
        plugins.forEach { plugin ->
            from(plugin.thinArchiveFile) {
                into("plugins")
                rename { plugin.bundledFileName }
            }
        }
    }
    notCompatibleWithConfigurationCache("Release archives discover plugin manifests at configuration time.")
}

val cleanRelease by tasks.registering(Delete::class) {
    delete(releaseOutputDirectory)
}

val assembleAppOnly by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Builds the standalone application JAR without plugins."
    dependsOn(cleanRelease, appArchive)
    into(releaseOutputDirectory)
    from(appArchiveFile)
    rename("QTranslate.jar", "QTranslate-App-${releaseVersion.get()}.jar")
}

val minimalPlugins = bundledPlugins.filter { it.id in minimalPluginIds }
val missingMinimalPluginIds = minimalPluginIds - minimalPlugins.map { it.id }.toSet()
val validateMinimalPlugins by tasks.registering(ValidateRequiredPluginsTask::class) {
    missingPluginIds.set(missingMinimalPluginIds.sorted())
}
val assembleMinimal by tasks.registering(Zip::class) {
    configureBundle(minimalPlugins, "minimal")
    dependsOn(cleanRelease, validateMinimalPlugins)
}

val assembleFull by tasks.registering(Zip::class) {
    configureBundle(bundledPlugins, "full")
    dependsOn(cleanRelease)
}

val assembleIndividualPlugins by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Builds every plugin as an independently versioned JAR."
    dependsOn(cleanRelease)
    dependsOn(bundledPlugins.map { "${it.projectPath}:shadowJar" })
    into(releaseOutputDirectory.map { it.dir("plugins") })
    bundledPlugins.forEach { plugin ->
        from(plugin.standaloneArchiveFile) {
            rename { plugin.releaseFileName }
        }
    }
    notCompatibleWithConfigurationCache("Plugin archives are discovered from plugin manifests.")
}

val releaseMetadata = linkedMapOf(
    "schemaVersion" to 1,
    "appVersion" to releaseVersion.get(),
    "variants" to listOf(
        mapOf("id" to "app-only", "file" to "QTranslate-App-${releaseVersion.get()}.jar", "plugins" to emptyList<String>()),
        mapOf("id" to "minimal", "file" to "QTranslate-Minimal-${releaseVersion.get()}.zip", "plugins" to minimalPlugins.map { it.id }),
        mapOf("id" to "full", "file" to "QTranslate-Full-${releaseVersion.get()}.zip", "plugins" to bundledPlugins.map { it.id }),
    ),
    "plugins" to bundledPlugins.map { plugin ->
        linkedMapOf(
            "id" to plugin.id,
            "name" to plugin.name,
            "version" to plugin.version,
            "minApiVersion" to plugin.minApiVersion,
            "file" to "plugins/${plugin.releaseFileName}",
            "bundledIn" to buildList {
                add("full")
                if (plugin.id in minimalPluginIds) add("minimal")
            },
        )
    },
)

val generateReleaseMetadata by tasks.registering(GenerateReleaseMetadataTask::class) {
    group = "distribution"
    description = "Writes machine-readable metadata for release variants and plugins."
    dependsOn(assembleAppOnly, assembleMinimal, assembleFull, assembleIndividualPlugins)
    metadataJson.set(JsonOutput.toJson(releaseMetadata))
    releaseDirectory.set(releaseOutputDirectory)
    artifactFiles.from(
        releaseOutputDirectory.map { directory ->
            buildList {
                add(directory.file("QTranslate-App-${releaseVersion.get()}.jar"))
                add(directory.file("QTranslate-Minimal-${releaseVersion.get()}.zip"))
                add(directory.file("QTranslate-Full-${releaseVersion.get()}.zip"))
                bundledPlugins.forEach { add(directory.file("plugins/${it.releaseFileName}")) }
            }
        },
    )
    outputFile.set(releaseOutputDirectory.map { it.file("release-metadata.json") })
}

val validateReleaseSizes by tasks.registering(ValidateReleaseSizesTask::class) {
    group = "verification"
    description = "Reports release sizes and fails when portable artifacts exceed their budgets."
    dependsOn(assembleAppOnly, assembleMinimal, assembleFull)
    appArtifact.set(releaseOutputDirectory.map { it.file("QTranslate-App-${releaseVersion.get()}.jar") })
    minimalArtifact.set(releaseOutputDirectory.map { it.file("QTranslate-Minimal-${releaseVersion.get()}.zip") })
    fullArtifact.set(releaseOutputDirectory.map { it.file("QTranslate-Full-${releaseVersion.get()}.zip") })
    maxAppBytes.set(55L * 1024 * 1024)
    maxMinimalBytes.set(50L * 1024 * 1024)
    maxFullBytes.set(52L * 1024 * 1024)
    maxBundledPluginsBytes.set(3L * 1024 * 1024)
    reportFile.set(releaseOutputDirectory.map { it.file("SIZE_REPORT.md") })
}

val generateReleaseChecksums by tasks.registering(GenerateReleaseChecksumsTask::class) {
    group = "distribution"
    description = "Writes SHA-256 checksums for every release artifact."
    dependsOn(generateReleaseMetadata, validateReleaseSizes)
    releaseDirectory.set(releaseOutputDirectory)
    outputFile.set(releaseOutputDirectory.map { it.file("SHA256SUMS.txt") })
}

tasks.register("assembleReleaseVariants") {
    group = "distribution"
    description = "Builds app-only, minimal, full, and individual plugin release artifacts."
    dependsOn(generateReleaseChecksums)
    notCompatibleWithConfigurationCache("Release assembly includes dynamic plugin artifacts.")
}

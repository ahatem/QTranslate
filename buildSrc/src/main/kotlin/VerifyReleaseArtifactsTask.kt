import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

abstract class VerifyReleaseArtifactsTask : DefaultTask() {
    @get:InputDirectory
    abstract val releaseDirectory: DirectoryProperty

    @get:Input
    abstract val expectedVersion: Property<String>

    @get:Input
    abstract val expectedPluginsJson: Property<String>

    @get:Input
    abstract val requireWindows: Property<Boolean>

    @get:Input
    abstract val requireMacHelpers: Property<Boolean>

    @TaskAction
    fun verify() {
        val root = releaseDirectory.get().asFile
        val version = expectedVersion.get()
        val expectedPlugins = objects(expectedPluginsJson.get())
        val metadata = obj(JsonSlurper().parse(root.resolve("release-metadata.json")))
        check(metadata["appVersion"] == version) { "Release metadata version differs from $version" }
        val variants = (metadata["variants"] as List<*>).map(::obj)
        check(variants.map { it["id"] }.toSet() == setOf("app-only", "portable"))
        val plugins = (metadata["plugins"] as List<*>).map(::obj)
        check(plugins.size == expectedPlugins.size && plugins.map { it["id"] }.toSet() == expectedPlugins.map { it["id"] }.toSet()) {
            "Release metadata plugin inventory differs from bundled manifests"
        }
        expectedPlugins.forEach { expected ->
            val actual = plugins.single { it["id"] == expected["id"] }
            listOf("id", "version", "minApiVersion", "file").forEach { key ->
                check(actual[key] == expected[key]) { "Plugin ${expected["id"]} has mismatched $key" }
            }
            check(actual["bundledIn"] == listOf("portable"))
            val jar = root.resolve(actual["file"] as String)
            ZipFile(jar).use { zip ->
                val manifest = obj(JsonSlurper().parseText(zip.getInputStream(zip.getEntry("plugin.json")
                    ?: error("Missing plugin.json in $jar")).bufferedReader().readText()))
                listOf("id", "version", "minApiVersion").forEach { key ->
                    check(manifest[key] == expected[key]) { "Plugin JAR manifest differs for ${expected["id"]}: $key" }
                }
            }
        }
        val app = "QTranslate-App-$version.jar"
        val portable = "QTranslate-$version.zip"
        check(variants.single { it["id"] == "app-only" }["file"] == app)
        check(variants.single { it["id"] == "portable" }["file"] == portable)
        check(variants.single { it["id"] == "portable" }["plugins"] == expectedPlugins.map { it["id"] })
        check(variants.single { it["id"] == "app-only" }["plugins"] == emptyList<String>())
        (variants + plugins).forEach { item ->
            val file = root.resolve(item["file"] as String)
            check(file.isFile && (item["sizeBytes"] as Number).toLong() == file.length() && item["sha256"] == hash(file)) {
                "Release metadata does not match ${item["file"]}"
            }
        }
        ZipFile(root.resolve(portable)).use { zip ->
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            val bundled = entries.filter { it.startsWith("QTranslate/plugins/") && it.endsWith(".jar") }
            check(bundled.size == expectedPlugins.size && bundled.toSet() == expectedPlugins.map {
                "QTranslate/plugins/${it["bundledFile"]}"
            }.toSet()) { "Portable plugin JAR inventory differs from bundled manifests" }
            listOf("QTranslate/QTranslate.jar", "QTranslate/portable-plugin-ids.txt", "QTranslate/LICENSE",
                "QTranslate/NOTICE.md").forEach { check(it in entries) { "Portable ZIP lacks $it" } }
            // The marker is what makes this archive portable rather than merely extractable; without
            // it the app would store its data in the OS user location and a moved copy would abandon it.
            check("QTranslate/portable.flag" in entries) { "Portable ZIP lacks portable.flag" }
            listOf("languages", "themes", "icons", "LICENSES", "THIRD_PARTY_LICENSES").forEach { dir ->
                check(entries.any { it.startsWith("QTranslate/$dir/") }) { "Portable ZIP lacks $dir" }
            }
            val inventory = zip.getInputStream(zip.getEntry("QTranslate/portable-plugin-ids.txt"))
                .bufferedReader().readLines().filter(String::isNotBlank)
            check(inventory.size == expectedPlugins.size && inventory.toSet() == expectedPlugins.map { it["id"] }.toSet())
            if (requireMacHelpers.get()) {
                val systemJar = zip.getInputStream(zip.getEntry("QTranslate/plugins/system-services-plugin.jar"))
                    .readBytes()
                val temp = File.createTempFile("qtranslate-system-services-", ".jar")
                try {
                    temp.writeBytes(systemJar)
                    ZipFile(temp).use { pluginZip ->
                        listOf("vision_ocr", "system_spell").forEach { helper ->
                            check(pluginZip.getEntry("native/macos/$helper") != null) { "Missing macOS helper $helper" }
                        }
                    }
                } finally { temp.delete() }
            }
        }
        check(root.resolve("SIZE_REPORT.md").readText().contains("| PASS |"))
        check(!root.resolve("SIZE_REPORT.md").readText().contains("| FAIL |"))
        val expectedFiles = buildSet {
            add(app); add(portable); add("release-metadata.json"); add("SIZE_REPORT.md")
            expectedPlugins.forEach { add(it["file"] as String) }
            if (requireWindows.get()) add("QTranslate-$version-windows-x64.zip")
        }
        val checksums = root.resolve("SHA256SUMS.txt").readLines().filter(String::isNotBlank).map { line ->
            val match = Regex("([0-9a-f]{64})  (.+)").matchEntire(line) ?: error("Malformed SHA256SUMS entry: $line")
            match.groupValues[2] to match.groupValues[1]
        }
        check(checksums.size == expectedFiles.size && checksums.map { it.first }.toSet() == expectedFiles) {
            "SHA256SUMS does not contain the exact release inventory"
        }
        val actualFiles = root.walkTopDown().filter(File::isFile).map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it != "SHA256SUMS.txt" }.toSet()
        check(actualFiles == expectedFiles) { "Release directory contains missing or unexpected artifacts: $actualFiles" }
        checksums.forEach { (name, digest) -> check(hash(root.resolve(name)) == digest) { "Checksum mismatch: $name" } }
        logger.lifecycle("Release inventory, metadata, portable resources, sizes, and ${checksums.size} checksums verified")
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): Map<String, Any?> = value as Map<String, Any?>

    private fun objects(json: String): List<Map<String, Any?>> = (JsonSlurper().parseText(json) as List<*>).map(::obj)
}

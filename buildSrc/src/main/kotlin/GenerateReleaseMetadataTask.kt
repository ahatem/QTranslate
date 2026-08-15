import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest

abstract class GenerateReleaseMetadataTask : DefaultTask() {
    @get:Input
    abstract val metadataJson: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifactFiles: ConfigurableFileCollection

    @get:Internal
    abstract val releaseDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        @Suppress("UNCHECKED_CAST")
        val parsed = JsonSlurper().parseText(metadataJson.get()) as MutableMap<String, Any?>
        val root = releaseDirectory.get().asFile
        val artifacts = artifactFiles.files.associateBy { it.relativeTo(root).invariantSeparatorsPath }
        sequenceOf("variants", "plugins").forEach { section ->
            @Suppress("UNCHECKED_CAST")
            (parsed[section] as List<MutableMap<String, Any?>>).forEach { item ->
                val artifact = artifacts[item["file"] as String] ?: return@forEach
                item["sizeBytes"] = artifact.length()
                item["sha256"] = sha256(artifact.readBytes())
            }
        }
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.prettyPrint(JsonOutput.toJson(parsed)) + "\n")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

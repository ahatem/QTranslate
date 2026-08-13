import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class GenerateReleaseChecksumsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val root = releaseDirectory.get().asFile
        val checksumFile = outputFile.get().asFile
        val artifacts = root.walkTopDown()
            .filter { it.isFile && it != checksumFile }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        val lines = artifacts.map { artifact ->
            val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
            val hash = digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            "$hash  ${artifact.relativeTo(root).invariantSeparatorsPath}"
        }
        checksumFile.apply {
            parentFile.mkdirs()
            writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }
}

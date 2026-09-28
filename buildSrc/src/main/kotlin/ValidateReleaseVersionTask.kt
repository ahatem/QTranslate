import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

fun appVersionFromSource(file: File): String =
    Regex("const val APP_VERSION = \"([^\"]+)\"")
        .find(file.readText())?.groupValues?.get(1)
        ?: error("Could not read APP_VERSION from $file")

abstract class ValidateReleaseVersionTask : DefaultTask() {
    @get:InputFile
    abstract val constantsFile: RegularFileProperty

    @get:Input
    abstract val expectedVersion: Property<String>

    @TaskAction
    fun validate() {
        val runtimeVersion = appVersionFromSource(constantsFile.get().asFile)
        val artifactVersion = expectedVersion.get()
        check(artifactVersion == runtimeVersion) {
            "Release version $artifactVersion differs from runtime/About/updater version $runtimeVersion"
        }
        check(Regex("(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?").matches(artifactVersion)) {
            "Release version is not a semantic version: $artifactVersion"
        }
        logger.lifecycle("Release version $artifactVersion matches runtime, About, and updater identity")
    }
}

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class ValidateReleaseClassVersionsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifacts: ConfigurableFileCollection

    @get:Input
    abstract val maximumMajor: Property<Int>

    @TaskAction
    fun validate() {
        val files = artifacts.files.sortedBy { it.name }
        require(files.isNotEmpty()) { "No release artifacts were provided for class-version verification" }
        val failures = mutableListOf<String>()
        files.forEach { artifact ->
            val result = ReleaseClassVersionVerifier.verify(artifact, maximumMajor.get())
            check(result.inspectedClasses > 0) { "No classes found in release artifact: ${artifact.name}" }
            logger.lifecycle("${artifact.name}: ${result.inspectedClasses} classes, highest major ${result.highestMajor}")
            failures += result.violations
        }
        if (failures.isNotEmpty()) {
            throw GradleException(
                "Release artifacts require a newer Java runtime than Java ${maximumMajor.get() - 44}:\n" +
                    failures.take(30).joinToString("\n") +
                    if (failures.size > 30) "\n... and ${failures.size - 30} more" else ""
            )
        }
    }
}

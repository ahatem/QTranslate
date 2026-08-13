import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.util.Locale
import java.util.zip.ZipFile

abstract class ValidateReleaseSizesTask : DefaultTask() {
    @get:InputFile
    abstract val appArtifact: RegularFileProperty

    @get:InputFile
    abstract val minimalArtifact: RegularFileProperty

    @get:InputFile
    abstract val fullArtifact: RegularFileProperty

    @get:Input
    abstract val maxAppBytes: Property<Long>

    @get:Input
    abstract val maxMinimalBytes: Property<Long>

    @get:Input
    abstract val maxFullBytes: Property<Long>

    @get:Input
    abstract val maxBundledPluginsBytes: Property<Long>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun validate() {
        val app = appArtifact.get().asFile
        val minimal = minimalArtifact.get().asFile
        val full = fullArtifact.get().asFile
        val bundledPluginsBytes = ZipFile(full).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.matches(Regex("QTranslate/plugins/.*-plugin\\.jar")) }
                .sumOf { it.size }
        }
        val measurements = listOf(
            Measurement("App JAR", app.length(), maxAppBytes.get()),
            Measurement("Minimal ZIP", minimal.length(), maxMinimalBytes.get()),
            Measurement("Full ZIP", full.length(), maxFullBytes.get()),
            Measurement("Bundled plugin JARs", bundledPluginsBytes, maxBundledPluginsBytes.get())
        )
        val report = buildString {
            appendLine("# Release size report")
            appendLine()
            appendLine("| Artifact | Size | Budget | Status |")
            appendLine("|---|---:|---:|:---:|")
            measurements.forEach { measurement ->
                appendLine(
                    "| ${measurement.name} | ${measurement.size.toMiB()} MiB | " +
                        "${measurement.budget.toMiB()} MiB | ${if (measurement.passes) "PASS" else "FAIL"} |"
                )
            }
        }
        reportFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(report)
        }
        logger.lifecycle(report)
        val failures = measurements.filterNot(Measurement::passes)
        check(failures.isEmpty()) {
            "Release size budget exceeded: " + failures.joinToString { "${it.name} ${it.size.toMiB()} MiB" }
        }
    }

    private data class Measurement(val name: String, val size: Long, val budget: Long) {
        val passes get() = size <= budget
    }

    private fun Long.toMiB(): String = String.format(Locale.ROOT, "%.2f", this / 1024.0 / 1024.0)
}

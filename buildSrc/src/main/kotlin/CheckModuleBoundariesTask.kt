import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Asserts that every declared project dependency respects the module ownership rules in the
 * repository guide (AGENTS.md): `:api` depends on no project, `:core` and `:plugins:common` only
 * on `:api`, `:ui-swing` on `:api` and `:core`, `:app` on the modules it composes, and service
 * plugins only on `:api` and `:plugins:common`.
 *
 * The edge inventory is computed at configuration time from the project model — without resolving
 * any dependency — and handed to this task as plain strings. The task itself holds no reference to
 * a Project, which keeps it configuration-cache friendly and safe to run from any task graph.
 */
abstract class CheckModuleBoundariesTask : DefaultTask() {
    /**
     * Violations as "owner -> dependency (configuration)" strings. A non-empty set fails the task.
     */
    @get:Input
    abstract val forbiddenEdges: SetProperty<String>

    /**
     * Every verified edge as "owner -> dependency (configuration)" strings, recorded in the report.
     */
    @get:Input
    abstract val verifiedEdges: SetProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun check() {
        val forbidden = forbiddenEdges.get().sorted()
        val edges = verifiedEdges.get().sorted()
        reportFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine(if (forbidden.isEmpty()) "Module boundary check: OK" else "Module boundary check: FAILED")
                    appendLine("Verified ${edges.size} declared project edges against the AGENTS.md ownership rules.")
                    if (forbidden.isNotEmpty()) {
                        appendLine()
                        appendLine("Forbidden project dependencies (the allowed edges are the matrix in build.gradle.kts):")
                        forbidden.forEach { appendLine("  $it") }
                    }
                    appendLine()
                    edges.forEach { appendLine(it) }
                }
            )
        }
        if (forbidden.isNotEmpty()) {
            throw GradleException(
                "Forbidden project dependencies (the allowed edges are the matrix in build.gradle.kts):\n" +
                    forbidden.joinToString(separator = "\n", prefix = "  ")
            )
        }
        logger.lifecycle("Module boundary check passed: {} declared project edges verified", edges.size)
    }
}

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class ValidateRequiredPluginsTask : DefaultTask() {
    @get:Input
    abstract val missingPluginIds: ListProperty<String>

    @TaskAction
    fun validate() {
        val missing = missingPluginIds.get()
        check(missing.isEmpty()) {
            "Minimal distribution requires missing plugin module(s): ${missing.joinToString()}"
        }
    }
}

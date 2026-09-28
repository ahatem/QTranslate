import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GeneratePortablePluginInventoryTask : DefaultTask() {
    @get:Input
    abstract val pluginIds: ListProperty<String>

    @get:OutputFile
    abstract val inventoryFile: RegularFileProperty

    @TaskAction
    fun generate() {
        inventoryFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(pluginIds.get().joinToString("\n", postfix = "\n"))
        }
    }
}

package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.Container
import java.io.File
import java.nio.file.Files
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hotkey table has to grow with the display. It is measured in pixels, which Swing does not
 * scale, so every number that sizes it goes through [UIScale] the way the History table's do.
 */
class KeyboardPanelScalingTest {
    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-kbd-test").toFile(), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() {
        UIScale.setZoomFactor(1f)
        scopes.forEach { it.cancel() }
    }

    private fun store(): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-kbd-store").toFile()
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        repository.updateConfiguration(Configuration.DEFAULT)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        SettingsStore(repository, logger, scope, Configuration.DEFAULT)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun tableAt(zoom: Float): JTable {
        UIScale.setZoomFactor(zoom)
        val settings = store()
        val panel = onEdt { KeyboardPanel(settings, localizer) }
        onEdt { panel.render(settings.state.value) }
        return panel.tableForTest()
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    private fun layOut(table: JTable, width: Int) = onEdt {
        table.setSize(width, table.rowHeight * table.rowCount)
        table.doLayout()
    }

    @Test
    fun `row height follows the display scale`() {
        val normal = tableAt(1f).rowHeight
        val large = tableAt(2f).rowHeight
        assertEquals(34, normal, "the authored row at 100%")
        assertEquals(68, large, "a 200% display doubles the row")
    }

    @Test
    fun `column limits scale together and stay consistent at every zoom`() {
        listOf(1f, 1.25f, 1.5f, 2f).forEach { zoom ->
            val table = tableAt(zoom)
            (0 until table.columnCount).forEach { index ->
                val column = table.columnModel.getColumn(index)
                assertTrue(column.minWidth <= column.preferredWidth, "zoom $zoom column $index: min <= preferred")
                assertTrue(column.preferredWidth <= column.maxWidth, "zoom $zoom column $index: preferred <= max")
            }
            assertEquals(UIScale.scale(125), table.columnModel.getColumn(1).minWidth, "zoom $zoom: hotkey minimum")
        }
    }

    @Test
    fun `extra width goes to the action column, not to scope`() {
        val table = tableAt(1f)
        layOut(table, 1000)

        val action = table.columnModel.getColumn(0).width
        val hotkey = table.columnModel.getColumn(1).width
        val scope = table.columnModel.getColumn(2).width
        assertTrue(scope <= UIScale.scale(120), "scope stays a narrow column ($scope)")
        assertTrue(hotkey <= UIScale.scale(320), "hotkey stays capped ($hotkey)")
        assertTrue(action > hotkey && action > scope, "action has the useful remaining width ($action)")
        assertEquals(1000, action + hotkey + scope, "the columns fill the table")
    }

    @Test
    fun `a narrow viewport still leaves every column usable`() {
        val table = tableAt(1f)
        layOut(table, UIScale.scale(420))
        (0 until 3).forEach { index ->
            val column = table.columnModel.getColumn(index)
            assertTrue(column.width >= column.minWidth, "column $index keeps its minimum (${column.width})")
        }
    }

    @Test
    fun `at a large scale the rows still hold a key chip without clipping it`() {
        val table = tableAt(2f)
        layOut(table, UIScale.scale(600))

        val binding = table.model.getValueAt(0, 1)
        val renderer = table.getCellRenderer(0, 1)
        val rendered = onEdt { renderer.getTableCellRendererComponent(table, binding, false, false, 0, 1) as Container }
        onEdt {
            rendered.setSize(table.columnModel.getColumn(1).width, table.rowHeight)
            layoutTree(rendered)
        }
        val chips = descendants(rendered).filterIsInstance<JLabel>()
        assertTrue(chips.isNotEmpty())
        chips.forEach { chip ->
            val top = SwingUtilities.convertPoint(chip, 0, 0, rendered).y
            assertTrue(chip.height in 1..table.rowHeight, "chip height ${chip.height} fits the ${table.rowHeight} row")
            assertTrue(top >= 0 && top + chip.height <= table.rowHeight, "chip sits vertically inside the row")
        }
    }

    @Test
    fun `no settings or history table sets a bare pixel row height`() {
        val root = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing")
        val sources = root.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size > 100, "the scan reads the sources (${sources.size})")
        // A property assignment on its own line, as in `rowHeight = 28`. WrapLayout keeps a running
        // total of that name while it measures a row, which is arithmetic and not a table metric.
        val assignment = Regex("""(?m)^\s*rowHeight\s*=\s*\d""")
        val offenders = sources.filter { it.name != "WrapLayout.kt" }
            .filter { assignment.containsMatchIn(it.readText()) }.map { it.name }
        assertTrue(offenders.isEmpty(), "a row height is measured in pixels and must go through UIScale: $offenders")
    }

    @Test
    fun `no raw unscaled row height remains in the panel`() {
        val source = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/settings/panels/KeyboardPanel.kt").readText()
        assertTrue(Regex("""rowHeight\s*=\s*\d""").findAll(source).none(), "rowHeight is never a bare number")
        assertTrue("* 34" !in source && "34 +" !in source, "no 34-pixel row arithmetic is left")
        assertTrue("UIScale.scale(ROW_HEIGHT)" in source)
    }
}

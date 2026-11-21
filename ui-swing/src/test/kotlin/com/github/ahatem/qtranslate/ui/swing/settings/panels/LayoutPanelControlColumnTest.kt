package com.github.ahatem.qtranslate.ui.swing.settings.panels

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
import java.awt.ComponentOrientation
import java.awt.Container
import java.nio.file.Files
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Layout page keeps one control column for its own rows, and the service selector — nested
 * under its sub-section heading — gets its own, indented past it. The audit reported per-row x
 * values that differed; measured here, the difference is the hierarchy rather than drift: every
 * row at a given level shares an edge, and the nested level is offset from it in both directions.
 *
 * The page's own rows are panes, toolbar, close and minimise; the selector's row is the only one
 * inside a sub-section, because which widget picks the service is subordinate to the arrangement
 * above it — a comparison board has no selector at all. The status bar is a checkbox rather than a
 * picker, so it is not counted here.
 */
class LayoutPanelControlColumnTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-layout-test").toFile(), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun store(): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-layout-store").toFile()
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

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    /** The page, built, rendered and laid out at a realistic size in [orientation]. */
    private fun builtPanel(
        orientation: ComponentOrientation = ComponentOrientation.LEFT_TO_RIGHT,
    ): LayoutPanel {
        val store = store()
        return onEdt {
            LayoutPanel(store, localizer).also {
                it.applyComponentOrientation(orientation)
                it.render(store.state.value)
                it.setSize(900, 700)
                layoutTree(it)
            }
        }
    }

    private fun controlEdges(orientation: ComponentOrientation): List<Int> {
        val panel = builtPanel(orientation)
        return descendants(panel).filterIsInstance<JComboBox<*>>()
            .map { SwingUtilities.convertPoint(it, 0, 0, panel).x }
    }

    @Test
    fun `the page's own pickers share one column edge`() {
        val edges = controlEdges(ComponentOrientation.LEFT_TO_RIGHT)
        assertEquals(5, edges.size, "panes, toolbar, the service selector, close and minimise ($edges)")

        val columns = edges.groupingBy { it }.eachCount()
        assertEquals(
            2,
            columns.size,
            "one column for the page's rows and one for the nested service selector: $columns",
        )
        assertEquals(
            listOf(4, 1),
            columns.values.sortedDescending(),
            "four rows at page level, one inside the sub-section: $columns",
        )
    }

    /**
     * Every row of the page starts at the page's edge, and the sub-section's single row starts
     * exactly one indent inside it.
     *
     * Measured on the row labels rather than on the pickers. The two containers size their label
     * columns independently — the page's as wide as its longest row name, the sub-section's as
     * wide as its own — so comparing picker x values measures two label widths as much as it
     * measures nesting, and that comparison flipped sign when the sub-section was left with one
     * short row name. A label starts at its row's leading edge, so its offset *is* the nesting.
     */
    @Test
    fun `the page's rows share one edge and the nested row is indented past it`() {
        val panel = builtPanel()
        fun leadingOffset(text: String): Int {
            val label = descendants(panel).filterIsInstance<JLabel>().single { it.text == text }
            return SwingUtilities.convertPoint(label, 0, 0, panel).x
        }

        val pageRows = listOf(
            localizer.getString("settings_window.layout_row_panes"),
            localizer.getString("settings_window.layout_row_toolbar"),
            localizer.getString("settings_window.close_button"),
            localizer.getString("settings_window.minimize_button"),
        ).map(::leadingOffset)

        assertEquals(1, pageRows.distinct().size, "the page's own rows share a leading edge ($pageRows)")
        assertEquals(
            SettingsPanel.NESTED_INDENT,
            leadingOffset(localizer.getString("settings_window.layout_row_selector")) - pageRows.first(),
            "the sub-section's row sits one hierarchy indent inside the page's own rows",
        )
    }

    @Test
    fun `the column edges survive right-to-left mirroring`() {
        val edges = controlEdges(ComponentOrientation.RIGHT_TO_LEFT)

        // Not the same assertion as the left-to-right case. A control here is laid out with
        // weightX and horizontal fill, so in a right-to-left interface it stretches back to the
        // leading edge and every one of them starts at the same x whatever row it is on: the x of a
        // control says nothing about nesting in that direction. The nesting is measured on the row
        // labels in `the nesting indents from the leading edge in both directions` instead.
        assertEquals(5, edges.size, "all five pickers are still on the page ($edges)")
        assertEquals(1, edges.distinct().size, "controls fill from the leading edge in this direction ($edges)")
    }

    /**
     * The nested level is offset from the leading (data) edge, whichever edge that is.
     *
     * Measured on the row *labels* rather than on the controls. A picker sits beside its label, so
     * its coordinate also moves with the label's own width, and a right-to-left comparison of
     * controls therefore reads as inverted even when the nesting is in the right place. A label
     * starts at its row's leading edge, so its offset is the indentation itself.
     */
    @Test
    fun `the nesting indents from the leading edge in both directions`() {
        listOf(ComponentOrientation.LEFT_TO_RIGHT, ComponentOrientation.RIGHT_TO_LEFT).forEach { orientation ->
            val (page, nested) = labelLeadingOffsets(orientation)
            assertTrue(
                nested > page,
                "nested rows start further in than the page's own rows in $orientation ($nested vs $page)",
            )
            assertEquals(
                SettingsPanel.NESTED_INDENT,
                nested - page,
                "the offset is the hierarchy's indent rather than an accident of label widths, in $orientation",
            )
        }
    }

    /** Leading-edge offsets of a page-level row's label and one inside the sub-section. */
    private fun labelLeadingOffsets(orientation: ComponentOrientation): Pair<Int, Int> {
        val panel = builtPanel(orientation)
        fun offsetOf(text: String): Int {
            val label = descendants(panel)
                .filterIsInstance<JLabel>()
                .single { it.text == text }
            val x = SwingUtilities.convertPoint(label, 0, 0, panel).x
            return if (orientation.isLeftToRight) x else panel.width - (x + label.width)
        }
        return offsetOf(localizer.getString("settings_window.layout_row_panes")) to
            offsetOf(localizer.getString("settings_window.layout_row_selector"))
    }
}

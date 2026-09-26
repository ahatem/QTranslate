package com.github.ahatem.qtranslate.ui.swing.shared

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the shared combo renderer and toolbar button are used, and where hand-rolled hover used to
 * be. The Quick popups and status chrome need a display and a full icon manager to construct, so
 * these read the sources, as the neighbouring comparison tests do.
 */
class InteractionFoundationSourceTest {
    private val root = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing")

    private fun source(path: String) = File(root, path).readText()

    private fun allSources() = root.walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun `no combo renderer returns a bare label`() {
        val offenders = allSources().filter { Regex("""setRenderer\s*\{[^}]*JLabel\(""").containsMatchIn(it.readText()) }
        assertEquals(emptyList(), offenders.map { it.name }, "combo renderers must delegate to the native list renderer")
    }

    @Test
    fun `settings pages render their combos through the shared renderer`() {
        listOf("General", "Languages", "Layout", "Translation", "Appearance", "Services").forEach { page ->
            assertTrue(
                source("settings/panels/${page}Panel.kt").contains("DisplayValueRenderer"),
                "$page page must use the shared combo renderer"
            )
        }
    }

    @Test
    fun `layout picker keeps comparison eligibility separate from rendering`() {
        val layout = source("settings/panels/LayoutPanel.kt")
        assertTrue(layout.contains("isDisabled = ::isUnavailableComparison"))
        assertTrue(layout.contains("layout.id == LayoutPresetIds.COMPARISON && !comparisonAvailable"))
    }

    @Test
    fun `quick translate actions use the shared toolbar button and native pin state`() {
        val quick = source("quicktranslate/QuickTranslateDialog.kt")
        listOf("pinButton", "listenButton", "copyButton", "closeButton").forEach {
            assertTrue(quick.contains("private val $it = createToolbarButton("), "$it must be a shared toolbar button")
        }
        assertTrue(quick.contains("pinButton.isSelected = pinned"), "pinned state is the button's own selected state")
        assertFalse(quick.contains("isContentAreaFilled"), "content area must stay fillable for hover")
        assertFalse(quick.contains("mouseEntered"), "hover must come from the look and feel")
        assertTrue(quick.contains("closeButton.addActionListener { onDismiss() }"), "close keeps its action")
    }

    @Test
    fun `quick dictionary actions use the shared toolbar button and native pin state`() {
        val quick = source("dictionary/QuickDictionaryDialog.kt")
        listOf("pinButton", "closeButton").forEach {
            assertTrue(quick.contains("private val $it = createToolbarButton("), "$it must be a shared toolbar button")
        }
        assertTrue(quick.contains("private val autoSourceButton = createToolbarButton()"))
        assertTrue(quick.contains("pinButton.isSelected = pinned"), "pinned state is the button's own selected state")
        assertFalse(quick.contains("isContentAreaFilled"), "content area must stay fillable for hover")
        assertFalse(quick.contains("mouseEntered"), "hover must come from the look and feel")
        assertTrue(quick.contains("closeButton.addActionListener { currentState?.onClose?.invoke() }"), "close keeps its action")
    }

    @Test
    fun `error detail close keeps its action without custom hover`() {
        val popup = source("main/statusbar/ErrorDetailPopup.kt")
        assertTrue(popup.contains("private val closeButton = createToolbarButton("))
        assertFalse(popup.contains("mouseEntered"))
        assertFalse(popup.contains("isContentAreaFilled"))
        assertTrue(popup.contains("closeButton.addActionListener { popup.isVisible = false }"))
    }

    @Test
    fun `status and dictionary icon actions are reachable from the keyboard`() {
        assertTrue(source("main/statusbar/StatusBar.kt").contains("createToolbarButton(iconManager, Icons.NOTIFICATION"))
        assertTrue(source("dictionary/DictionaryResultView.kt").contains("createToolbarButton(iconManager, LISTEN_ICON"))
        assertTrue(source("dictionary/DictionaryPanel.kt").contains("private val autoSourceButton = createToolbarButton()"))
    }
}

package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsPanel
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsState
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The normal Output is one result surface, the same one Extra Output uses: a single scroll pane
 * that owns the text, so the text wraps to the width it is given and never spills out of it.
 */
class OutputSurfaceTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-output-test").toFile(), LanguageTomlParser(), logger)
    }

    private fun blindIconManager(): IconManager {
        val theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null)
        val allocate = theUnsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, IconManager::class.java) as IconManager
    }

    private val textFont = FontConfig("Dialog", 14)

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun outputPanel() = onEdt {
        OutputTextPanel(blindIconManager(), localizer, onListen = {}, onTranslateRequest = {})
    }

    private fun extraPanel() = onEdt {
        ExtraOutputPanel(blindIconManager(), localizer, onListen = {}, onTranslateRequest = {})
    }

    private fun OutputTextPanel.show(text: String, width: Int, height: Int = 320) {
        onEdt {
            render(
                OutputTextState(
                    text = text, fontConfig = textFont, fallbackFontConfig = textFont,
                    isLoading = false, actionsState = TextActionsState(emptyList())
                )
            )
            setSize(width, height)
            layoutTree(this)
        }
        // Text direction is applied from a queued task once the document has changed.
        onEdt { layoutTree(this) }
    }

    /** Lays the tree out top down, which is what a validated window does, without needing a window. */
    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    private fun scrollPanes(root: Container) = descendants(root).filterIsInstance<JScrollPane>()

    private val longEnglish = List(60) { "translation" }.joinToString(" ")
    private val longArabic = List(60) { "الحركة الدودية" }.joinToString(" ")
    private val unbroken = "x".repeat(400)

    @Test
    fun `the panel owns one scroll pane and nothing outside wraps it in another`() {
        val panel = outputPanel()
        assertEquals(1, scrollPanes(panel).size, "Output has exactly one scroll owner")
    }

    @Test
    fun `the layouts mount the output panel itself, not a scroll pane around it`() {
        val leaves = List(8) { javax.swing.JPanel() }
        val registry = com.github.ahatem.qtranslate.ui.swing.main.layout.ComponentRegistry(
            historyBar = leaves[0], inputPanel = leaves[1], languageBar = leaves[2],
            outputPanel = leaves[3], compareBoard = leaves[4], extraOutputPanel = leaves[5],
            translatorSelector = leaves[6], statusBar = leaves[7]
        )
        listOf(
            com.github.ahatem.qtranslate.ui.swing.main.layout.ClassicLayout,
            com.github.ahatem.qtranslate.ui.swing.main.layout.SideBySideLayout,
        ).forEach { layout ->
            onEdt { layout.arrange(registry, isRtl = false) }
            assertTrue(leaves[3].parent !is JViewport, "${layout.id}: Output must not sit inside a viewport")
            assertTrue(
                ancestors(leaves[3]).none { it is JScrollPane },
                "${layout.id}: Output must not have a layout-owned scroll pane above it"
            )
        }
    }

    private fun ancestors(component: Component) = generateSequence(component.parent) { it.parent }.toList()

    @Test
    fun `output and extra output are built from the same result surface`() {
        val root = java.io.File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main")
        val output = java.io.File(root, "output/OutputTextPanel.kt").readText()
        val extra = java.io.File(root, "output/ExtraOutputPanel.kt").readText()
        val surface = "ReadOnlyTextPanel(textPane, actionsPanel)"
        assertTrue(surface in output, "Output uses the shared result surface")
        assertTrue(surface in extra, "Extra Output uses the shared result surface")

        val surfaceSource = java.io.File(root, "widgets/ReadOnlyTextPanel.kt").readText()
        assertEquals(1, "JScrollPane(".let { token -> surfaceSource.windowed(token.length).count { it == token } }, "and that surface scrolls itself once")
        assertFalse("scrollable" in surfaceSource.substringAfter("class ReadOnlyTextPanel("), "with no switch to turn that off")
    }

    private fun assertWrapsInside(text: String, width: Int) {
        val panel = outputPanel()
        panel.show(text, width)

        // Sizes are read on the event thread: asking a text component for its size takes the
        // document's read lock, which the thread that lays text out may be holding.
        val scroll = scrollPanes(panel).single()
        val pane = panel.textPaneComponent
        assertSame(scroll.viewport, pane.parent, "the text pane sits directly in the scroll pane's viewport")
        assertTrue(scroll.viewport.width > 0)
        assertEquals(scroll.viewport.width, pane.width, "the text pane is exactly as wide as its viewport")
        assertFalse(scroll.horizontalScrollBar.isVisible, "ordinary prose never scrolls sideways")
        val paneHeight = onEdt { pane.preferredSize.height }
        assertTrue(paneHeight > onEdt { pane.getFontMetrics(pane.font).height } * 2, "long text wraps onto several lines")
        val requested = onEdt { panel.preferredSize.width }
        assertTrue(requested <= width, "the panel does not ask for more width ($requested) than it was given ($width)")
    }

    @Test
    fun `long English text tracks a narrow viewport`() = assertWrapsInside(longEnglish, 240)

    @Test
    fun `long Arabic text tracks a narrow viewport`() = assertWrapsInside(longArabic, 240)

    @Test
    fun `text without spaces still stays inside the viewport`() = assertWrapsInside(unbroken, 240)

    @Test
    fun `Arabic text is right to left while an English one stays left to right`() {
        val arabic = outputPanel().also { it.show(longArabic, 240) }
        val english = outputPanel().also { it.show(longEnglish, 240) }

        assertFalse(onEdt { arabic.textPaneComponent.componentOrientation.isLeftToRight })
        assertTrue(onEdt { english.textPaneComponent.componentOrientation.isLeftToRight })
        // The panel around it is not the text's direction.
        assertTrue(onEdt { arabic.componentOrientation.isLeftToRight })
    }

    @Test
    fun `the direction of the text survives the interface being mirrored`() {
        val english = outputPanel().also { it.show(longEnglish, 240) }
        val arabic = outputPanel().also { it.show(longArabic, 240) }

        // What the frame does when the interface is Arabic: every component is told to mirror.
        onEdt { english.applyComponentOrientation(java.awt.ComponentOrientation.RIGHT_TO_LEFT) }
        english.show(longEnglish, 240)
        assertTrue(onEdt { english.textPaneComponent.componentOrientation.isLeftToRight }, "English stays left to right")

        onEdt { arabic.applyComponentOrientation(java.awt.ComponentOrientation.LEFT_TO_RIGHT) }
        arabic.show(longArabic, 240)
        assertFalse(onEdt { arabic.textPaneComponent.componentOrientation.isLeftToRight }, "Arabic stays right to left")
    }

    @Test
    fun `an empty pane follows the interface until it has text`() {
        val panel = outputPanel()
        onEdt { panel.applyComponentOrientation(java.awt.ComponentOrientation.RIGHT_TO_LEFT) }
        assertFalse(onEdt { panel.textPaneComponent.componentOrientation.isLeftToRight })
    }

    @Test
    fun `the action column stays beside the text and inside the panel`() {
        val actions = onEdt { TextActionsPanel(blindIconManager()).also { it.add(JButton("copy")) } }
        val text = onEdt { com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane({}, {}, {}) }
        val readOnly = onEdt { com.github.ahatem.qtranslate.ui.swing.main.widgets.ReadOnlyTextPanel(text, actions) }
        onEdt {
            readOnly.setSize(240, 200)
            layoutTree(readOnly)
        }

        val scroll = scrollPanes(readOnly).single()
        val actionsLeft = SwingUtilities.convertPoint(actions, 0, 0, readOnly).x
        assertTrue(actions.width > 0 && actionsLeft + actions.width <= readOnly.width, "the actions fit inside the panel")
        assertTrue(scroll.x + scroll.width <= actionsLeft, "the actions are not covered by the text")
        val button = descendants(actions).filterIsInstance<JButton>().single()
        assertTrue(button.width > 0 && button.height > 0, "the action itself is laid out and reachable")
        assertTrue((button as JComponent).isVisible)
    }
}

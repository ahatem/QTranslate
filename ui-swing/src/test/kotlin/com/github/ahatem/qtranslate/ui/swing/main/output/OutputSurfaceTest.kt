package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsState
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The Output text wraps to the width it is given, keeps its own direction and starts at the top. */
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

    private fun ancestors(component: Component) = generateSequence(component.parent) { it.parent }.toList()

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
    fun `long English, Arabic and unbroken text all track a narrow viewport`() {
        listOf(longEnglish, longArabic, unbroken).forEach { assertWrapsInside(it, 240) }
    }

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
    fun `a new translation starts at the top of the output view`() {
        val panel = outputPanel()
        panel.show(longEnglish, 320)
        val scroll = scrollPanes(panel).single()
        val textPane = panel.textPaneComponent as javax.swing.JTextPane
        // Simulate the reader having scrolled down, as setText's end-of-text caret caused.
        onEdt {
            textPane.caretPosition = textPane.document.length
            scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum
        }
        assertTrue(onEdt { scroll.verticalScrollBar.value } > 0, "the view was scrolled down")

        onEdt {
            panel.render(
                OutputTextState(
                    text = "a brand new", fontConfig = textFont, fallbackFontConfig = textFont,
                    isLoading = false, actionsState = TextActionsState(emptyList())
                )
            )
        }
        // The reset is queued behind the deferred font pass; a fresh EDT round drains both.
        onEdt { }

        assertEquals(0, onEdt { textPane.caretPosition })
        assertEquals(0, onEdt { scroll.verticalScrollBar.value })
    }
}

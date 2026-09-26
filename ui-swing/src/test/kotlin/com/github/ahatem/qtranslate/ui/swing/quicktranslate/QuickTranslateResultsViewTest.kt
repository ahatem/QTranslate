package com.github.ahatem.qtranslate.ui.swing.quicktranslate

import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.main.output.CompareBoard
import com.github.ahatem.qtranslate.ui.swing.main.output.CompareBoardState
import com.github.ahatem.qtranslate.ui.swing.main.output.ProviderPresentation
import com.github.ahatem.qtranslate.ui.swing.main.output.ProviderRole
import com.github.ahatem.qtranslate.ui.swing.main.output.ProviderStatus
import com.github.ahatem.qtranslate.ui.swing.main.output.TranslationProviderState
import com.github.ahatem.qtranslate.ui.swing.main.output.TranslationProviderView
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import java.awt.Component
import java.awt.Container
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QuickTranslateResultsViewTest {
    private val font = FontConfig("Dialog", 14)

    private fun provider(
        id: String,
        name: String,
        role: ProviderRole,
        text: String = "$id result",
        status: ProviderStatus = ProviderStatus.SUCCESS,
        definition: String = ""
    ) = TranslationProviderState(
        serviceId = id,
        serviceName = name,
        iconPath = null,
        role = role,
        presentation = ProviderPresentation.QUICK,
        status = status,
        text = text,
        loadingText = "Translating...",
        failureText = "Translation failed",
        copyLabel = "Copy",
        listenLabel = "Listen",
        stopLabel = "Stop",
        primaryLabel = "Primary",
        definition = definition,
        fontConfig = font,
        fallbackFontConfig = font,
        onCopy = {}
    )

    private fun boardWith(primary: TranslationProviderState, secondaries: List<TranslationProviderState>): CompareBoard {
        val board = CompareBoard(iconManager = null)
        SwingUtilities.invokeAndWait {
            board.render(CompareBoardState(primary = primary, secondaries = secondaries))
        }
        SwingUtilities.invokeAndWait { }
        return board
    }

    @Test
    fun `primary definition and comparisons share the popup result viewport`() {
        val board = boardWith(
            provider("primary", "Primary", ProviderRole.PRIMARY, "primary", definition = "a definition"),
            listOf(provider("secondary", "Secondary", ProviderRole.SECONDARY, "secondary"))
        )
        val view = QuickTranslateResultsView(board)

        assertEquals(1, descendants(view).filterIsInstance<JScrollPane>().size)
        assertSame(view.viewport, descendants(view).filterIsInstance<JScrollPane>().single())
        assertTrue(isDescendant(view.viewport.viewport.view, board))
        assertTrue(isDescendant(view.viewport.viewport.view, board.primaryProviderView.textPaneComponent))
    }

    @Test
    fun `definition belongs to the primary ahead of the secondaries`() {
        val board = boardWith(
            provider("primary", "Primary", ProviderRole.PRIMARY, "primary", definition = "a definition"),
            listOf(provider("secondary", "Secondary", ProviderRole.SECONDARY, "secondary"))
        )
        QuickTranslateResultsView(board)

        val primaryAreas = descendants(board.primaryProviderView).filterIsInstance<JTextArea>().map { it.text }
        assertTrue(primaryAreas.contains("a definition"))
        val secondary = board.secondaryViewForTest("secondary")!!
        assertTrue(descendants(secondary).filterIsInstance<JTextArea>().none { it.text == "a definition" })
        assertTrue(board.orderedServiceIdsForTest().indexOf("primary") < board.orderedServiceIdsForTest().indexOf("secondary"))
    }

    @Test
    fun `quick board always stacks a single column`() {
        val board = boardWith(
            provider("primary", "Primary", ProviderRole.PRIMARY, "primary"),
            listOf(
                provider("one", "One", ProviderRole.SECONDARY),
                provider("two", "Two", ProviderRole.SECONDARY)
            )
        )
        SwingUtilities.invokeAndWait {
            board.setSize(1000, 800)
            board.doLayout()
        }
        SwingUtilities.invokeAndWait { }

        assertFalse(board.isWideForTest())
        val views = board.components.filterIsInstance<TranslationProviderView>()
        assertEquals(1, views.map { it.x }.toSet().size)
    }

    @Test
    fun `sibling completion preserves the successful provider in place`() {
        val board = boardWith(
            provider("primary", "Primary", ProviderRole.PRIMARY, "primary"),
            listOf(
                provider("steady", "Steady", ProviderRole.SECONDARY, "steady text"),
                provider("late", "Late", ProviderRole.SECONDARY, "", ProviderStatus.LOADING)
            )
        )
        lateinit var steadyPane: AdvancedTextPane
        SwingUtilities.invokeAndWait {
            steadyPane = board.secondaryViewForTest("steady")!!.textPaneForTest()
            steadyPane.select(0, 3)
        }
        SwingUtilities.invokeAndWait {
            board.render(
                CompareBoardState(
                    primary = provider("primary", "Primary", ProviderRole.PRIMARY, "primary"),
                    secondaries = listOf(
                        provider("steady", "Steady", ProviderRole.SECONDARY, "steady text"),
                        provider("late", "Late", ProviderRole.SECONDARY, "late text")
                    )
                )
            )
        }
        SwingUtilities.invokeAndWait { }

        // The popup keeps its size and scrolls instead: the same components persist.
        assertSame(steadyPane, board.secondaryViewForTest("steady")!!.textPaneForTest())
        assertEquals(0, steadyPane.selectionStart)
        assertEquals(3, steadyPane.selectionEnd)
    }

    private fun isDescendant(root: Component?, target: Component): Boolean =
        root === target || (root is Container && root.components.any { isDescendant(it, target) })

    private fun descendants(component: Component): List<Component> =
        listOf(component) + if (component is Container) component.components.flatMap(::descendants) else emptyList()
}

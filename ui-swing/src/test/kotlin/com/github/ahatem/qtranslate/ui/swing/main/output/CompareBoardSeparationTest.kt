package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import java.awt.Component
import java.awt.Container
import javax.swing.JSeparator
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The vertical board divider exists only in the two-column layout; Quick Translate never uses it. */
class CompareBoardSeparationTest {
    private val font = FontConfig("Dialog", 14)

    private fun primary(presentation: ProviderPresentation = ProviderPresentation.MAIN) =
        TranslationProviderState(
            serviceId = "google",
            serviceName = "Google",
            iconPath = null,
            role = ProviderRole.PRIMARY,
            presentation = presentation,
            status = ProviderStatus.SUCCESS,
            text = "primary result",
            loadingText = "Translating...",
            failureText = "Translation failed",
            copyLabel = "Copy",
            listenLabel = "Listen",
            stopLabel = "Stop",
            primaryLabel = "Primary",
            placeholderTitle = "Translate to compare results",
            placeholderSubtitle = "4 translation services ready",
            fontConfig = font,
            fallbackFontConfig = font
        )

    private fun secondary(
        id: String,
        presentation: ProviderPresentation = ProviderPresentation.MAIN
    ) = TranslationProviderState(
        serviceId = id,
        serviceName = id.replaceFirstChar { it.uppercase() },
        iconPath = null,
        role = ProviderRole.SECONDARY,
        presentation = presentation,
        status = ProviderStatus.SUCCESS,
        text = "$id result",
        loadingText = "Translating...",
        failureText = "Translation failed",
        copyLabel = "Copy",
        fontConfig = font,
        fallbackFontConfig = font,
        onCopy = {}
    )

    private fun renderOnEdt(block: () -> Unit) {
        SwingUtilities.invokeAndWait(block)
        SwingUtilities.invokeAndWait { }
    }

    private fun layout(board: CompareBoard, width: Int, height: Int = 900) {
        renderOnEdt {
            board.setSize(width, height)
            repeat(3) { layoutTopDown(board) }
        }
    }

    private fun layoutTopDown(component: Component) {
        component.doLayout()
        if (component is Container) component.components.forEach(::layoutTopDown)
    }

    private fun boardWith(vararg ids: String): CompareBoard {
        val board = CompareBoard(iconManager = null)
        renderOnEdt {
            board.render(
                CompareBoardState(
                    primary = primary(),
                    secondaries = ids.map { secondary(it) }
                )
            )
        }
        return board
    }

    @Test
    fun `resizing from wide to narrow swaps the center divider for section rules`() {
        val board = boardWith("one", "two", "three", "four")
        layout(board, 1000)
        assertTrue(board.centerDividerForTest().isVisible)

        layout(board, 480)
        assertFalse(board.isWideForTest())
        assertFalse(board.centerDividerForTest().isVisible)
        assertEquals(3, board.sectionRulesForTest().count { it.isVisible })
    }

    @Test
    fun `quick board separates sections horizontally with no vertical divider`() {
        val board = CompareBoard(iconManager = null)
        renderOnEdt {
            board.render(
                CompareBoardState(
                    primary = primary(ProviderPresentation.QUICK),
                    secondaries = listOf(
                        secondary("one", ProviderPresentation.QUICK),
                        secondary("two", ProviderPresentation.QUICK)
                    )
                )
            )
        }
        layout(board, 1000)

        assertFalse(board.isWideForTest(), "quick always stacks a single column")
        assertFalse(board.centerDividerForTest().isVisible)
        assertTrue(
            descendants(board).filterIsInstance<JSeparator>()
                .none { it.isVisible && it.orientation == SwingConstants.VERTICAL }
        )
        assertTrue(board.primaryBoundaryForTest().isVisible)
        assertEquals(1, board.sectionRulesForTest().count { it.isVisible })
    }

    private fun descendants(component: Component): List<Component> =
        listOf(component) + if (component is Container) {
            component.components.flatMap(::descendants)
        } else emptyList()
}

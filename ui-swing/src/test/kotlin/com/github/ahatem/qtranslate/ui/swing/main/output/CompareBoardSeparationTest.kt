package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Container
import javax.swing.JSeparator
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder
import javax.swing.border.MatteBorder
import javax.swing.border.TitledBorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Section-rule separation for PR #292: neutral FlatLaf-native dividers group
 * providers without cards, boxes, or custom painting.
 *
 * Structural assertions only — no pixel or screenshot comparisons.
 */
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

    private fun visibleRules(board: CompareBoard): List<JSeparator> =
        (listOf(board.primaryBoundaryForTest()) + board.sectionRulesForTest())
            .filter { it.isVisible }

    // 1. Single-column secondaries have neutral separation between sections.
    @Test
    fun `single column places one neutral rule between each pair of secondaries`() {
        val board = boardWith("one", "two", "three")
        layout(board, 480)

        val rules = board.sectionRulesForTest().filter { it.isVisible }
        assertEquals(2, rules.size, "three secondaries need exactly two inter-section rules")
        rules.forEach { rule ->
            // FlatLaf-native primitive: exactly JSeparator, no custom subclass.
            assertEquals(JSeparator::class.java, rule.javaClass)
            assertEquals(SwingConstants.HORIZONTAL, rule.orientation)
        }

        val views = listOf("one", "two", "three").map { board.secondaryViewForTest(it)!! }
        rules.forEachIndexed { i, rule ->
            assertTrue(rule.y > views[i].y + views[i].height, "rule must sit below section ${i + 1}")
            assertTrue(rule.y + rule.height < views[i + 1].y, "rule must sit above section ${i + 2}")
            assertEquals(views[i].x, rule.x)
            assertEquals(views[i].width, rule.width)
        }
    }

    // 2. No full rectangular provider border is introduced.
    @Test
    fun `providers stay borderless sections with no card outlines`() {
        val board = boardWith("one", "two")
        layout(board, 1000)

        board.components.filterIsInstance<TranslationProviderView>().forEach { view ->
            val border = view.border
            assertTrue(
                border == null || border is EmptyBorder,
                "provider sections must not gain rectangular borders"
            )
        }
        fun hasBoxBorder(component: Component): Boolean =
            (component is javax.swing.JComponent && (component.border is LineBorder ||
                component.border is MatteBorder || component.border is TitledBorder)) ||
                (component is Container && component.components.any(::hasBoxBorder))
        assertFalse(hasBoxBorder(board), "no cards, outlines, or grid borders anywhere on the board")
    }

    // 3. Two-column mode creates exactly one central vertical board separator.
    @Test
    fun `wide mode shows exactly one central vertical divider`() {
        val board = boardWith("one", "two", "three", "four")
        layout(board, 1000)

        assertTrue(board.isWideForTest())
        val divider = board.centerDividerForTest()
        assertTrue(divider.isVisible, "center divider exists only in two-column mode")
        assertEquals(JSeparator::class.java, divider.javaClass)
        assertEquals(SwingConstants.VERTICAL, divider.orientation)

        val verticals = descendants(board).filterIsInstance<JSeparator>()
            .filter { it.isVisible && it.orientation == SwingConstants.VERTICAL }
        assertEquals(1, verticals.size, "one central divider, not per-provider side borders")

        val left = board.secondaryViewForTest("one")!!
        val right = board.secondaryViewForTest("two")!!
        assertTrue(divider.x > left.x + left.width, "divider clears the left lane")
        assertTrue(divider.x + divider.width < right.x, "divider clears the right lane")
        val tops = listOf("one", "two").map { board.secondaryViewForTest(it)!!.y }.min()
        val bottoms = board.components.filterIsInstance<TranslationProviderView>()
            .drop(1).map { it.y + it.height }.max()
        assertTrue(divider.y <= tops, "divider spans from the secondary block top")
        assertTrue(divider.y + divider.height >= bottoms - divider.height - 20, "divider spans the useful secondary height")
    }

    // 4. Single-column mode has no central vertical separator.
    @Test
    fun `narrow mode has no vertical board divider`() {
        val board = boardWith("one", "two", "three")
        layout(board, 480)

        assertFalse(board.isWideForTest())
        assertFalse(board.centerDividerForTest().isVisible)
        assertTrue(
            descendants(board).filterIsInstance<JSeparator>()
                .none { it.isVisible && it.orientation == SwingConstants.VERTICAL }
        )
    }

    // 5. Primary-to-secondary boundary exists when secondary results are present.
    @Test
    fun `primary boundary spans the board between primary and secondaries`() {
        val board = boardWith("one", "two")
        layout(board, 480)

        val boundary = board.primaryBoundaryForTest()
        assertTrue(boundary.isVisible)
        assertEquals(JSeparator::class.java, boundary.javaClass)
        assertEquals(SwingConstants.HORIZONTAL, boundary.orientation)
        assertEquals(board.primaryProviderView.x, boundary.x)
        assertEquals(board.primaryProviderView.width, boundary.width)
        val primaryBottom = board.primaryProviderView.y + board.primaryProviderView.height
        val firstTop = board.secondaryViewForTest("one")!!.y
        assertTrue(boundary.y > primaryBottom, "boundary sits below the primary section")
        assertTrue(boundary.y + boundary.height < firstTop, "boundary sits above the secondaries")
    }

    @Test
    fun `lone primary and empty state render no dividers`() {
        val board = CompareBoard(iconManager = null)
        renderOnEdt {
            board.render(CompareBoardState(primary = primary(), secondaries = emptyList()))
        }
        layout(board, 480)

        assertFalse(board.primaryBoundaryForTest().isVisible)
        assertFalse(board.centerDividerForTest().isVisible)
        assertTrue(visibleRules(board).isEmpty())
    }

    // 6. No redundant trailing separator after the final secondary.
    @Test
    fun `no rule trails after the final secondary section`() {
        val board = boardWith("one", "two", "three")
        layout(board, 480)

        val last = board.secondaryViewForTest("three")!!
        val lastBottom = last.y + last.height
        visibleRules(board).forEach { rule ->
            assertTrue(rule.y < lastBottom, "every rule must precede the final section end")
        }
        // Exactly the primary boundary plus one rule per section gap.
        assertEquals(3, visibleRules(board).size)
    }

    // 7 + 8. Resizing across the wide threshold removes and restores the divider.
    @Test
    fun `resizing wide to narrow removes the center divider`() {
        val board = boardWith("one", "two", "three", "four")
        layout(board, 1000)
        assertTrue(board.centerDividerForTest().isVisible)

        layout(board, 480)
        assertFalse(board.isWideForTest())
        assertFalse(board.centerDividerForTest().isVisible)
        // Single-column rules take over; nothing is orphaned or duplicated.
        assertEquals(3, board.sectionRulesForTest().count { it.isVisible })
    }

    @Test
    fun `resizing narrow to wide restores the center divider`() {
        val board = boardWith("one", "two", "three", "four")
        layout(board, 480)
        assertFalse(board.centerDividerForTest().isVisible)

        layout(board, 1000)
        assertTrue(board.isWideForTest())
        assertTrue(board.centerDividerForTest().isVisible)
        val verticals = descendants(board).filterIsInstance<JSeparator>()
            .count { it.isVisible && it.orientation == SwingConstants.VERTICAL }
        assertEquals(1, verticals)
    }

    // 9. RTL wide mode keeps reading-order columns with one central separator.
    @Test
    fun `rtl wide mode keeps first provider leading with one central divider`() {
        val board = boardWith("one", "two")
        renderOnEdt { board.componentOrientation = ComponentOrientation.RIGHT_TO_LEFT }
        layout(board, 1000)

        assertTrue(board.isWideForTest())
        val first = board.secondaryViewForTest("one")!!
        val second = board.secondaryViewForTest("two")!!
        assertTrue(first.x > second.x, "first provider belongs on the reading-order side")
        assertTrue(board.centerDividerForTest().isVisible)
        assertEquals(
            1,
            descendants(board).filterIsInstance<JSeparator>()
                .count { it.isVisible && it.orientation == SwingConstants.VERTICAL }
        )
        renderOnEdt { board.componentOrientation = ComponentOrientation.LEFT_TO_RIGHT }
    }

    // 10. Quick Translate stays single-column with no vertical divider.
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

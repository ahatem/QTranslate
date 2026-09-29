package com.github.ahatem.qtranslate.ui.swing.main.layout

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.border.LineBorder
import javax.swing.border.MatteBorder
import javax.swing.border.TitledBorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Layout-owned Comparison / Extra Output boundary (#292).
 *
 * A plain theme-aware separator composed by the layout — never a panel
 * border, never per-provider boxes, never custom painting.
 */
class ComparisonExtraBoundaryTest {
    private fun registry(): List<JPanel> = List(8) { JPanel() }

    private fun components(leaves: List<JPanel>) = ComponentRegistry(
        historyBar = leaves[0], inputPanel = leaves[1], languageBar = leaves[2],
        outputPanel = leaves[3], compareBoard = leaves[4], extraOutputPanel = leaves[5],
        translatorSelector = leaves[6], statusBar = leaves[7]
    )

    private fun descendants(component: Component): List<Component> =
        listOf(component) + if (component is Container) {
            component.components.flatMap(::descendants)
        } else emptyList()

    /** Separators mounted directly above the extra panel leaf. */
    private fun extraBoundaries(root: Container, extraPanel: JComponent): List<JSeparator> =
        descendants(root).filterIsInstance<JSeparator>().filter { separator ->
            val parent = separator.parent ?: return@filter false
            parent.components.contains(extraPanel)
        }

    // 22. Comparison with visible Extra Output has a layout-owned separator.
    @Test
    fun `comparison layout owns a separator above extra output`() {
        val leaves = registry()
        lateinit var arranged: ArrangedLayout
        SwingUtilities.invokeAndWait {
            arranged = ComparisonLayout.arrange(components(leaves), isRtl = false)
        }

        val boundaries = extraBoundaries(arranged.rootComponent, leaves[5])
        assertEquals(1, boundaries.size, "exactly one layout-owned boundary above Extra Output")
        assertEquals(JSeparator::class.java, boundaries.single().javaClass)
        assertEquals(SwingConstants.HORIZONTAL, boundaries.single().orientation)
    }

    // 23. The boundary is not a per-provider rectangular border.
    @Test
    fun `boundary uses a separator not box borders`() {
        val leaves = registry()
        lateinit var arranged: ArrangedLayout
        SwingUtilities.invokeAndWait {
            arranged = ComparisonLayout.arrange(components(leaves), isRtl = false)
        }

        val boxed = descendants(arranged.rootComponent).filterIsInstance<JComponent>().filter { component ->
            component.border is LineBorder ||
                component.border is MatteBorder ||
                component.border is TitledBorder
        }
        assertTrue(boxed.isEmpty(), "no rectangular borders anywhere in the comparison composition")
    }

    // 24. Hiding Extra Output removes the separation with it.
    @Test
    fun `hiding extra output hides the boundary too`() {
        val leaves = registry()
        lateinit var arranged: ArrangedLayout
        SwingUtilities.invokeAndWait {
            arranged = ComparisonLayout.arrange(components(leaves), isRtl = false)
            leaves[5].isVisible = true
        }
        assertEquals(1, extraBoundaries(arranged.rootComponent, leaves[5]).count { it.isVisible })

        SwingUtilities.invokeAndWait {
            arranged.componentRefs.updateExtraOutputVisibility(false, leaves[5])
        }
        SwingUtilities.invokeAndWait { }
        SwingUtilities.invokeAndWait { }

        assertFalse(leaves[5].isVisible)
        assertTrue(
            extraBoundaries(arranged.rootComponent, leaves[5]).none { it.isVisible },
            "no stray separation line may survive a hidden Extra Output"
        )

        SwingUtilities.invokeAndWait {
            arranged.componentRefs.updateExtraOutputVisibility(true, leaves[5])
        }
        SwingUtilities.invokeAndWait { }
        SwingUtilities.invokeAndWait { }
        assertEquals(1, extraBoundaries(arranged.rootComponent, leaves[5]).count { it.isVisible })
    }

    // 25. Layout switching leaves no stale separators.
    @Test
    fun `switching layouts never duplicates or orphans the boundary`() {
        val container = JPanel(BorderLayout())
        val leaves = registry()
        val manager = LayoutManager(components(leaves), container)

        listOf("comparison", "classic", "comparison", "side_by_side", "comparison").forEach { id ->
            SwingUtilities.invokeAndWait { manager.switchLayout(id) }
            SwingUtilities.invokeAndWait { }
            SwingUtilities.invokeAndWait { }
            SwingUtilities.invokeAndWait { }
            val visible = extraBoundaries(container, leaves[5]).count { it.isVisible }
            if (id == "comparison") {
                assertEquals(1, visible, "comparison must own exactly one visible boundary")
            } else {
                assertEquals(0, visible, "$id must not retain a comparison boundary")
            }
        }
        // The extra leaf itself is still mounted exactly once everywhere.
        assertEquals(1, descendants(container).count { it === leaves[5] })
    }

    @Test
    fun `classic and side by side keep their boundary-free composition`() {
        val leaves = registry()
        SwingUtilities.invokeAndWait {
            val classic = ClassicLayout.arrange(components(leaves), isRtl = false)
            assertTrue(extraBoundaries(classic.rootComponent, leaves[5]).isEmpty())
            val sideBySide = SideBySideLayout.arrange(components(leaves), isRtl = false)
            assertTrue(extraBoundaries(sideBySide.rootComponent, leaves[5]).isEmpty())
        }
    }
}

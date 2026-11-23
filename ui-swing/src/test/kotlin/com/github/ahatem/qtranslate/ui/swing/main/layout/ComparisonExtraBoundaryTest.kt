package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Hiding Extra Output in the Comparison layout takes the separator above it along (#292). */
class ComparisonExtraBoundaryTest {
    private fun leaves(): List<JPanel> = List(8) { JPanel() }

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

    @Test
    fun `hiding extra output hides the boundary too`() {
        val leaves = leaves()
        lateinit var arranged: ArrangedLayout
        SwingUtilities.invokeAndWait {
            arranged = SlotLayout.arrange(components(leaves), WindowLayout(panes = PaneLayout.COMPARISON))
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
}

package com.github.ahatem.qtranslate.ui.swing.main.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.TestIcons
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import java.awt.ComponentOrientation
import javax.swing.AbstractButton
import javax.swing.JMenuItem
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The action column folds what does not fit into a More menu instead of clipping it.
 */
class TextActionsOverflowTest {

    private val iconManager = TestIcons.iconManager()
    private val clicked = mutableListOf<String>()

    private val ids = listOf(
        "history_back", "history_forward", "input_image_translate", "input_document_translate",
        "input_translate", "input_clear", "copy_input", "listen_input",
    )

    private fun action(id: String, enabled: Boolean = true) = Action(
        id = id,
        iconPath = Icons.COPY,
        tooltip = "Label of $id",
        isEnabled = enabled,
        isVisible = true,
        onClick = { clicked += id },
    )

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun panel(
        height: Int,
        disabled: Set<String> = emptySet(),
        rtl: Boolean = false,
    ): TextActionsPanel = onEdt {
        TextActionsPanel(iconManager).also { panel ->
            if (rtl) panel.componentOrientation = ComponentOrientation.RIGHT_TO_LEFT
            panel.render(TextActionsState(ids.map { action(it, it !in disabled) }, moreLabel = "More actions"))
            panel.setSize(panel.preferredSize.width, height)
            panel.doLayout()
        }
    }

    private fun resize(panel: TextActionsPanel, height: Int) = onEdt {
        panel.setSize(panel.preferredSize.width, height)
        panel.doLayout()
    }

    private fun moreShown(panel: TextActionsPanel) = panel.moreButton.parent === panel && panel.moreButton.isVisible

    private fun buttons(panel: TextActionsPanel): List<AbstractButton> =
        panel.components.filterIsInstance<AbstractButton>().filter { it !== panel.moreButton }

    private fun fullHeight(): Int = onEdt { TextActionsPanel(iconManager).also {
        it.render(TextActionsState(ids.map { id -> action(id) }))
    }.preferredSize.height }

    @Test
    fun `a tall strip shows every action and no More button`() {
        val panel = panel(fullHeight() + 12)
        assertEquals(ids, buttons(panel).filter { it.isVisible }.map { it.name })
        assertFalse(moreShown(panel))
        assertEquals(emptyList(), panel.overflowedActionIds)
        assertTrue(panel.components.none { it === panel.moreButton }, "a strip that fits holds nothing but its actions")
        buttons(panel).forEach { assertTrue(it.y + it.height <= panel.height, "${it.name} is inside the strip") }
    }

    @Test
    fun `a short strip keeps the leading actions and folds the rest into More in order`() {
        val panel = panel(fullHeight() / 2)
        val shown = buttons(panel).filter { it.isVisible }.map { it.name }
        val folded = panel.overflowedActionIds

        assertTrue(moreShown(panel))
        assertTrue(shown.isNotEmpty() && folded.isNotEmpty())
        assertEquals(ids, shown + folded, "every action is somewhere, in its original order")

        // Nothing is clipped: each visible control, More included, ends inside the strip, and More
        // follows the last action that is shown.
        (buttons(panel).filter { it.isVisible } + panel.moreButton).forEach {
            assertTrue(it.y >= 0 && it.y + it.height <= panel.height, "${it.name} is inside the strip")
        }
        assertTrue(panel.moreButton.y >= buttons(panel).filter { it.isVisible }.maxOf { it.y + it.height })
        assertNotNull(panel.moreButton.toolTipText)
        assertNotNull(panel.moreButton.icon, "the More button is drawn")
    }

    @Test
    fun `an action in the More menu has the same text, icon, enabled state and action`() {
        val panel = panel(fullHeight() / 2, disabled = setOf("listen_input"))
        val folded = panel.overflowedActionIds
        assertTrue("listen_input" in folded && "copy_input" in folded)

        val menu = onEdt { panel.buildOverflowMenu() }
        val items = menu.components.filterIsInstance<JMenuItem>()
        assertEquals(folded, items.map { it.name })
        items.forEach { item ->
            val button = buttons(panel).first { it.name == item.name }
            assertEquals(button.toolTipText, item.text)
            assertEquals(button.icon, item.icon)
            assertEquals(button.isEnabled, item.isEnabled)
        }
        assertFalse(items.first { it.name == "listen_input" }.isEnabled)

        clicked.clear()
        onEdt { items.first { it.name == "copy_input" }.doClick(0) }
        assertEquals(listOf("copy_input"), clicked, "the item runs the action the button runs")
    }

    @Test
    fun `the split follows a resize`() {
        val panel = panel(fullHeight() / 2)
        val before = panel.overflowedActionIds.size
        resize(panel, fullHeight() / 3)
        assertTrue(panel.overflowedActionIds.size > before, "a shorter strip folds more")

        resize(panel, fullHeight() + 4)
        assertEquals(emptyList(), panel.overflowedActionIds)
        assertFalse(moreShown(panel))
        assertEquals(ids, buttons(panel).filter { it.isVisible }.map { it.name }, "growing brings every action back")
    }

    @Test
    fun `the More menu has the strip's reading direction`() {
        val rtl = panel(fullHeight() / 2, rtl = true)
        assertTrue(onEdt { rtl.buildOverflowMenu() }.componentOrientation.isLeftToRight.not())
        assertEquals(ids, buttons(rtl).filter { it.isVisible }.map { it.name } + rtl.overflowedActionIds)

        val ltr = panel(fullHeight() / 2)
        assertTrue(onEdt { ltr.buildOverflowMenu() }.componentOrientation.isLeftToRight)
    }
}

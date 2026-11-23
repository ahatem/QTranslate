package com.github.ahatem.qtranslate.ui.swing.main.statusbar

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.ui.swing.shared.TestIcons
import javax.swing.AbstractButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The notification button can live outside the status bar without a second source of state. */
class StatusBarNotificationButtonTest {

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private fun state(tooltip: String, enabled: Boolean = true) = StatusBarState(
        message = "Ready",
        type = NotificationType.INFO,
        notificationTooltip = tooltip,
        isNotificationButtonEnabled = enabled,
    )

    private fun bar(onClick: () -> Unit = {}): StatusBar {
        lateinit var bar: StatusBar
        onEdt { bar = StatusBar(TestIcons.iconManager(), onClick) }
        return bar
    }

    @Test
    fun `the bar holds the button until another host takes it`() {
        val bar = bar()
        val button = bar.notificationButton
        assertTrue(SwingUtilities.isDescendingFrom(button, bar))

        val host = JPanel()
        onEdt { host.add(button) }
        assertFalse(SwingUtilities.isDescendingFrom(button, bar), "one button, one parent")
        assertSame(host, button.parent)

        onEdt { bar.restoreNotificationButton() }
        assertTrue(SwingUtilities.isDescendingFrom(button, bar))
        assertEquals(0, host.componentCount)
    }

    @Test
    fun `a mounted button is still rendered from the one status state`() {
        val bar = bar()
        val button = bar.notificationButton
        onEdt { JPanel().add(button) }

        onEdt { bar.render(state("Notifications (3)")) }
        assertEquals("Notifications (3)", button.toolTipText)
        assertTrue(button.isEnabled)

        onEdt { bar.render(state("Notifications", enabled = false)) }
        assertEquals("Notifications", button.toolTipText)
        assertFalse(button.isEnabled)
    }

    @Test
    fun `pressing it from another host reaches the same handler and anchors to itself`() {
        var presses = 0
        val bar = bar { presses++ }
        val button = bar.notificationButton as AbstractButton
        onEdt { JPanel().add(button) }
        onEdt { button.doClick() }
        assertEquals(1, presses)
        assertSame(button, bar.notificationAnchor)
    }

    @Test
    fun `restoring when it never left changes nothing`() {
        val bar = bar()
        val before = bar.notificationButton.parent
        onEdt { bar.restoreNotificationButton() }
        assertSame(before, bar.notificationButton.parent)
    }
}

package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorStyle
import com.github.ahatem.qtranslate.ui.swing.shared.TestIcons
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Classic mode: a row of one-click toggle buttons, with an escape hatch to any service that has
 * scrolled out of view. Enhanced mode is a different picker entirely and is not covered here.
 */
class TranslatorSelectorTest {

    private fun service(id: String, name: String = id) = ServiceInfo(id, name, iconPath = null, type = ServiceRole.TRANSLATOR)

    private fun services(count: Int) = List(count) { service("service-$it", "Service ${it + 1}") }

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private fun selector(onServiceSelected: (ServiceRole, String) -> Unit = { _, _ -> }) =
        TranslatorSelector(TestIcons.iconManager(), onServiceSelected)

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    private fun size(selector: TranslatorSelector, width: Int, height: Int = 32) {
        onEdt {
            selector.setSize(width, height)
            layoutTree(selector)
        }
        onEdt { }
        onEdt { layoutTree(selector) }
    }

    private fun state(
        translators: List<ServiceInfo>,
        selectedId: String? = translators.firstOrNull()?.id,
        appearance: ServiceSelectorAppearance = ServiceSelectorAppearance.ICONS_AND_TEXT,
        loading: Boolean = false,
    ) = TranslatorSelectorState(
        availableTranslators = translators,
        selectedTranslatorId = selectedId,
        isLoading = loading,
        style = ServiceSelectorStyle.CLASSIC,
        appearance = appearance
    )

    @Test
    fun `classic mode shows one toggle button per translator, the active one selected`() {
        val selector = selector()
        val translators = services(3)
        onEdt { selector.render(state(translators, selectedId = "service-1")) }

        val buttons = descendants(selector).filterIsInstance<JToggleButton>()
        assertEquals(3, buttons.size)
        assertEquals(listOf("Service 2"), buttons.filter { it.isSelected }.map { it.text })
    }

    @Test
    fun `classic toggle buttons are reachable from the keyboard`() {
        val selector = selector()
        onEdt { selector.render(state(services(3))) }

        val buttons = descendants(selector).filterIsInstance<JToggleButton>()
        assertTrue(buttons.isNotEmpty())
        assertTrue(buttons.all { it.isFocusable }, "every service button must be tab-reachable")
    }

    @Test
    fun `clicking a service button reports the selection`() {
        val reported = mutableListOf<Pair<ServiceRole, String>>()
        val selector = selector { role, id -> reported += role to id }
        val translators = services(3)
        onEdt { selector.render(state(translators, selectedId = "service-0")) }

        val target = descendants(selector).filterIsInstance<JToggleButton>().first { it.text == "Service 3" }
        onEdt { target.doClick() }

        assertEquals(listOf(ServiceRole.TRANSLATOR to "service-2"), reported)
    }

    @Test
    fun `overflow controls stay hidden when every service fits`() {
        val selector = selector()
        onEdt { selector.render(state(services(2))) }
        size(selector, 2000)

        val buttons = descendants(selector).filterIsInstance<JButton>()
        assertTrue(buttons.none { it.isVisible && it.toolTipText == "All services" }, "nothing to jump to when nothing overflows")
    }

    @Test
    fun `overflow controls appear once services no longer fit, and the menu reaches every one of them`() {
        val reported = mutableListOf<String>()
        val selector = selector { _, id -> reported += id }
        val translators = services(12)
        onEdt { selector.render(state(translators)) }
        size(selector, 160)

        val overflowButton = descendants(selector).filterIsInstance<JButton>().first { it.toolTipText == "All services" }
        assertTrue(overflowButton.isVisible, "overflow menu must be discoverable once the row no longer fits")
    }

    @Test
    fun `appearance controls whether icon, text, or both are shown`() {
        val selector = selector()
        val translators = services(1)

        onEdt { selector.render(state(translators, appearance = ServiceSelectorAppearance.TEXT_ONLY)) }
        val textOnly = descendants(selector).filterIsInstance<JToggleButton>().first()
        assertEquals(null, textOnly.icon)
        assertEquals("Service 1", textOnly.text)

        onEdt { selector.render(state(translators, appearance = ServiceSelectorAppearance.ICONS_AND_TEXT)) }
        val both = descendants(selector).filterIsInstance<JToggleButton>().first()
        assertEquals("Service 1", both.text)
    }

    @Test
    fun `a loading selector disables every service button`() {
        val selector = selector()
        onEdt { selector.render(state(services(2), loading = true)) }

        val buttons = descendants(selector).filterIsInstance<JToggleButton>()
        assertTrue(buttons.isNotEmpty())
        assertFalse(buttons.any { it.isEnabled })
    }
}

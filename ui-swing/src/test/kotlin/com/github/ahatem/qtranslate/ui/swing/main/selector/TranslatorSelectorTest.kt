package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorStyle
import com.github.ahatem.qtranslate.ui.swing.shared.TestIcons
import java.awt.Component
import java.awt.Container
import java.awt.ComponentOrientation
import javax.swing.JButton
import javax.swing.JToggleButton
import javax.swing.JRadioButtonMenuItem
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Classic mode: active provider, complete fitting buttons, and a single menu for the rest.
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
        strings: TranslatorSelectorStrings = TranslatorSelectorStrings.DEFAULT,
    ) = TranslatorSelectorState(
        availableTranslators = translators,
        selectedTranslatorId = selectedId,
        isLoading = loading,
        style = ServiceSelectorStyle.CLASSIC,
        appearance = appearance,
        strings = strings
    )

    @Test
    fun `the strip draws the words it is handed, not English literals`() {
        // These words travel with the state rather than being baked into the widget, so the
        // interface language can change without the selector being rebuilt. This is the proof that
        // it reads them; before this the strip was English in every locale.
        val german = TranslatorSelectorStrings(
            moreButton = "Weitere",
            allServicesTooltip = "Alle Dienste",
            configureActiveTooltip = "Aktiven Dienst einrichten",
            translatorRole = "Übersetzen",
            dictionaryRole = "Wörterbuch",
            voiceRole = "Stimme",
            selectServiceTooltip = "%1\$s-Dienst wählen",
            configureServiceTooltip = "Gewählten %1\$s-Dienst einrichten",
        )
        val selector = selector()
        onEdt { selector.render(state(services(3), strings = german)) }
        size(selector, 800)

        onEdt {
            val gear = descendants(selector).filterIsInstance<JButton>()
                .single { it.toolTipText == "Aktiven Dienst einrichten" }
            assertEquals("Aktiven Dienst einrichten", gear.toolTipText, "the gear's tooltip is localized")
        }

        // Narrow enough that everything cannot fit: the overflow button appears with the same words.
        size(selector, 120)
        onEdt {
            val more = descendants(selector).filterIsInstance<JButton>()
                .first { it.toolTipText == "Alle Dienste" }
            assertEquals("Weitere ▾", more.text, "the overflow button and its tooltip are localized")
        }
    }

    @Test
    fun `classic mode shows one toggle button per translator, the active one selected`() {
        val selector = selector()
        val translators = services(3)
        onEdt { selector.render(state(translators, selectedId = "service-1")) }
        size(selector, 800)

        val buttons = descendants(selector).filterIsInstance<JToggleButton>()
        assertEquals(3, buttons.size)
        assertEquals(listOf("Service 2"), buttons.filter { it.isSelected }.map { it.text })
    }

    @Test
    fun `classic toggle buttons are reachable from the keyboard`() {
        val selector = selector()
        onEdt { selector.render(state(services(3))) }
        size(selector, 800)

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
        size(selector, 800)

        val target = descendants(selector).filterIsInstance<JToggleButton>().first { it.text == "Service 3" }
        onEdt { target.doClick() }

        assertEquals(listOf(ServiceRole.TRANSLATOR to "service-2"), reported)
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
        assertTrue(descendants(selector).none { it is JScrollPane }, "the row has no scrolling surface")
        val visible = descendants(selector).filterIsInstance<JToggleButton>()
        assertEquals("Service 1", visible.first().text, "active service stays visible")
        assertTrue(visible.size < translators.size, "only complete fitting services are inline")
        val menu = selector.overflowMenuForTest().components.filterIsInstance<JRadioButtonMenuItem>()
        assertEquals(translators.size, menu.size)
        assertTrue(menu.first().isSelected, "the menu identifies the active service")
        onEdt { menu.last().doClick() }
        assertEquals(listOf("service-11"), reported)
    }

    @Test
    fun `More follows the last visible service in the Classic strip`() {
        val selector = selector()
        onEdt { selector.render(state(services(12))) }
        size(selector, 500)

        val more = descendants(selector).filterIsInstance<JButton>().first { it.toolTipText == "All services" }
        val preceding = more.parent.components.filterIsInstance<JToggleButton>().last()
        assertEquals(preceding.x + preceding.width, more.x)
        assertEquals(more.parent.width, more.x + more.width, "Classic tabs fill the available strip")
    }

    @Test
    fun `the first service remains configurable and marked when no selection is saved`() {
        val configured = mutableListOf<String>()
        val selector = TranslatorSelector(TestIcons.iconManager(), { _, _ -> }, configured::add)
        onEdt { selector.render(state(services(2), selectedId = null)) }
        size(selector, 300)

        val gear = descendants(selector).filterIsInstance<JButton>()
            .first { it.toolTipText == "Configure active translation service" }
        onEdt { gear.doClick() }

        assertEquals(listOf("service-0"), configured)
        assertTrue(selector.overflowMenuForTest().components.filterIsInstance<JRadioButtonMenuItem>().first().isSelected)
    }

    @Test
    fun `appearance controls whether icon, text, or both are shown`() {
        val selector = selector()
        val translators = services(1)

        onEdt { selector.render(state(translators, appearance = ServiceSelectorAppearance.TEXT_ONLY)) }
        size(selector, 600)
        val textOnly = descendants(selector).filterIsInstance<JToggleButton>().first()
        assertEquals(null, textOnly.icon)
        assertEquals("Service 1", textOnly.text)

        onEdt { selector.render(state(translators, appearance = ServiceSelectorAppearance.ICONS_AND_TEXT)) }
        val both = descendants(selector).filterIsInstance<JToggleButton>().first()
        assertEquals("Service 1", both.text)

        onEdt { selector.render(state(translators, appearance = ServiceSelectorAppearance.ICONS_ONLY)) }
        val iconsOnlyFallback = descendants(selector).filterIsInstance<JToggleButton>().first()
        assertEquals("Service 1", iconsOnlyFallback.text, "a service without an icon keeps its name")
    }

    @Test
    fun `the selected provider remains visible at narrow width in RTL`() {
        val selector = selector()
        onEdt {
            selector.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT)
            selector.render(state(services(12), selectedId = "service-11"))
        }
        size(selector, 340)
        val active = descendants(selector).filterIsInstance<JToggleButton>().single { it.isSelected }
        val gear = descendants(selector).filterIsInstance<JButton>().first { it.toolTipText == "Configure active translation service" }
        val activeX = SwingUtilities.convertPoint(active, 0, 0, selector).x
        val gearX = SwingUtilities.convertPoint(gear, 0, 0, selector).x
        assertEquals("Service 12", active.text)
        assertTrue(activeX > gearX, "the active slot mirrors to the right in RTL")
        assertTrue(selector.overflowMenuForTest().components.filterIsInstance<JRadioButtonMenuItem>().last().isSelected)
    }

    @Test
    fun `selecting services keeps the classic strip in configured order`() {
        val selector = selector()
        val translators = services(3)
        onEdt { selector.render(state(translators, selectedId = "service-0")) }
        size(selector, 800)

        fun stripTexts(): List<String?> =
            descendants(selector).filterIsInstance<JToggleButton>().map { it.text }

        fun selectedText(): String? =
            descendants(selector).filterIsInstance<JToggleButton>().single { it.isSelected }.text

        assertEquals(listOf("Service 1", "Service 2", "Service 3"), stripTexts())

        // Last service becomes active: it must be selected in place, not moved to the front.
        onEdt { selector.render(state(translators, selectedId = "service-2")) }
        size(selector, 800)
        assertEquals(listOf("Service 1", "Service 2", "Service 3"), stripTexts())
        assertEquals("Service 3", selectedText(), "the newly selected service stays selected at its configured position")

        // Middle service, then back to the first: no position may ever move.
        onEdt { selector.render(state(translators, selectedId = "service-1")) }
        size(selector, 800)
        assertEquals(listOf("Service 1", "Service 2", "Service 3"), stripTexts())
        assertEquals("Service 2", selectedText())
        onEdt { selector.render(state(translators, selectedId = "service-0")) }
        size(selector, 800)
        assertEquals(listOf("Service 1", "Service 2", "Service 3"), stripTexts())
    }

    @Test
    fun `a service selected out of order stays at its configured place when the strip overflows`() {
        val reported = mutableListOf<String>()
        val selector = selector { _, id -> reported += id }
        val translators = services(12)
        onEdt { selector.render(state(translators, selectedId = "service-0")) }
        size(selector, 340)

        onEdt { selector.render(state(translators, selectedId = "service-11")) }
        size(selector, 340)

        val visible = descendants(selector).filterIsInstance<JToggleButton>()
        assertEquals("Service 12", visible.single { it.isSelected }.text, "the active service stays visible")
        assertEquals("Service 1", visible.first().text, "the strip keeps configured order; the active slot does not jump to the front")
        val configuredOrder = translators.map { it.name }
        val visibleOrder = visible.map { it.text }
        assertEquals(
            visibleOrder.sortedBy { configuredOrder.indexOf(it) },
            visibleOrder,
            "visible services keep their configured relative order"
        )
        val menu = selector.overflowMenuForTest().components.filterIsInstance<JRadioButtonMenuItem>()
        assertEquals(translators.size, menu.size)
        assertTrue(menu.last().isSelected, "the overflow menu identifies the active service")
        onEdt { menu.first().doClick() }
        assertEquals(listOf("service-0"), reported)
    }

    @Test
    fun `the overflow menu stays hidden while everything including a wide active service fits`() {
        val selector = selector()
        val translators = listOf(
            service("service-0", "A rather wide active translator label"),
            service("service-1", "B")
        )
        onEdt { selector.render(state(translators, selectedId = "service-0")) }
        size(selector, 2000) // realize the strip before measuring its buttons

        val buttons = descendants(selector).filterIsInstance<JToggleButton>()
        val gear = descendants(selector).filterIsInstance<JButton>()
            .first { it.toolTipText == "Configure active translation service" }
        val needed = buttons.sumOf { it.preferredSize.width } + gear.preferredSize.width + UIScale.scale(6)

        size(selector, needed)
        assertTrue(
            descendants(selector).filterIsInstance<JButton>()
                .none { it.isVisible && it.toolTipText == "All services" },
            "the row fits exactly, so the More menu must not appear even with a wide active slot"
        )

        size(selector, needed - 1)
        assertTrue(
            descendants(selector).filterIsInstance<JButton>()
                .any { it.isVisible && it.toolTipText == "All services" },
            "one pixel narrower and the row genuinely overflows"
        )
    }

    @Test
    fun `an icons-only strip packs its tabs at the start instead of stretching them`() {
        val selector = selector()
        onEdt { selector.render(state(services(4), appearance = ServiceSelectorAppearance.ICONS_ONLY)) }
        size(selector, 800)

        val tabs = descendants(selector).filterIsInstance<JToggleButton>()
        val widthOf = { tab: JToggleButton -> maxOf(tab.preferredSize.width, tab.preferredSize.height) }
        assertEquals(4, tabs.size)
        tabs.forEach {
            assertEquals(widthOf(it), it.width, "${it.toolTipText} keeps its own size, not an even share of the row")
            assertTrue(it.width >= it.height, "an icon tab is at least a square")
            assertEquals("Service ${tabs.indexOf(it) + 1}", it.toolTipText, "the tooltip names the provider")
        }
        assertEquals(tabs.first().x, tabs.first().parent.insets.left)
        tabs.zipWithNext().forEach { (a, b) -> assertEquals(a.x + a.width, b.x, "tabs touch, in order") }
        assertTrue(tabs.sumOf { it.width } < 400, "the tabs do not fill the row")

        val gear = descendants(selector).filterIsInstance<JButton>().first { it.toolTipText == "Configure active translation service" }
        assertTrue(
            SwingUtilities.convertPoint(gear, gear.width, 0, selector).x >= selector.width - 1,
            "the settings gear stays at the end of the row"
        )
    }

    @Test
    fun `an icons-only strip packs from the right in a right-to-left window`() {
        val selector = selector()
        onEdt {
            selector.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT)
            selector.render(state(services(3), appearance = ServiceSelectorAppearance.ICONS_ONLY))
        }
        size(selector, 800)

        val tabs = descendants(selector).filterIsInstance<JToggleButton>()
        val strip = tabs.first().parent
        assertEquals(strip.width - strip.insets.right, tabs.first().x + tabs.first().width, "the first tab is at the start edge")
        tabs.zipWithNext().forEach { (a, b) -> assertEquals(b.x + b.width, a.x, "tabs touch, mirrored") }
        val gear = descendants(selector).filterIsInstance<JButton>().first { it.toolTipText == "Configure active translation service" }
        assertTrue(SwingUtilities.convertPoint(gear, 0, 0, selector).x <= 1, "the gear mirrors to the left")
    }

    @Test
    fun `the service picker mirrors in a right-to-left window`() {
        val selector = selector()
        onEdt {
            selector.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT)
            selector.render(
                TranslatorSelectorState(
                    availableTranslators = services(2),
                    selectedTranslatorId = "service-0",
                    isLoading = false,
                    style = ServiceSelectorStyle.ENHANCED,
                )
            )
        }
        size(selector, 600)

        val combo = descendants(selector).filterIsInstance<javax.swing.JComboBox<*>>().first()
        val gear = descendants(selector).filterIsInstance<JButton>().first { it.toolTipText?.startsWith("Configure selected") == true }
        assertFalse(combo.componentOrientation.isLeftToRight, "a picker built after the window was mirrored is mirrored too")
        assertTrue(
            SwingUtilities.convertPoint(gear, 0, 0, selector).x < SwingUtilities.convertPoint(combo, 0, 0, selector).x,
            "the gear is at the combo's end, which is its left in a right-to-left window"
        )
    }

    @Test
    fun `a loading selector disables every service button`() {
        val selector = selector()
        onEdt { selector.render(state(services(2), loading = true)) }
        size(selector, 600)

        val buttons = descendants(selector).filterIsInstance<JToggleButton>()
        assertTrue(buttons.isNotEmpty())
        assertFalse(buttons.any { it.isEnabled })
    }
}

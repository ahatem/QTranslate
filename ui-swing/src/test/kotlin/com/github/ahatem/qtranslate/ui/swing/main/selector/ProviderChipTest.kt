package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.ui.swing.shared.TestIcons
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Container
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderChipTest {

    private fun service(id: String, name: String) = ServiceInfo(id, name, iconPath = null, type = ServiceRole.TRANSLATOR)
    private val services = listOf(service("google", "Google"), service("deepl", "DeepL"), service("bing", "Bing"))

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private fun chip(calls: MutableList<Pair<ServiceRole, String>> = mutableListOf()): ProviderChip {
        lateinit var chip: ProviderChip
        onEdt { chip = ProviderChip(TestIcons.iconManager()) { role, id -> calls += role to id } }
        return chip
    }

    private fun state(
        selected: String? = "deepl",
        loading: Boolean = false,
        list: List<ServiceInfo> = services,
        strings: TranslatorSelectorStrings = TranslatorSelectorStrings.DEFAULT,
    ) = TranslatorSelectorState(list, selected, loading, strings = strings)

    private fun walk(c: Component): List<Component> =
        listOf(c) + if (c is Container) c.components.flatMap(::walk) else emptyList()

    private fun listIn(root: Container): JList<*> =
        walk(root).filterIsInstance<JList<*>>().single { it.name == ProviderChip.LIST_NAME }

    @Test
    fun `the chip names the active service and draws its chevron`() {
        val chip = chip()
        onEdt { chip.render(state()) }
        assertEquals("DeepL", chip.text)
        assertTrue(chip.showChevron)
        assertEquals("Translation service", chip.toolTipText)
        assertTrue(chip.isEnabled)
    }

    @Test
    fun `with nothing selected it says so in the words it is handed`() {
        val chip = chip()
        onEdt {
            chip.render(state(selected = null, strings = TranslatorSelectorStrings.DEFAULT.copy(noTranslator = "Kein Dienst")))
        }
        assertEquals("Kein Dienst", chip.text)
    }

    @Test
    fun `the popup lists every translator with the active one marked, drawn by the shared renderer`() {
        val chip = chip()
        onEdt { chip.render(state()) }
        val list = listIn(assertNotNull(chip.popupForTest()))
        assertEquals(
            services.map { it.id },
            (0 until list.model.size).map { (list.model.getElementAt(it) as ServiceInfo).id }
        )
        assertEquals(1, list.selectedIndex, "the active service is highlighted")
        assertTrue(list.cellRenderer is ServiceListCellRenderer)
        @Suppress("UNCHECKED_CAST")
        val row = (list.cellRenderer as javax.swing.ListCellRenderer<Any?>)
            .getListCellRendererComponent(list as JList<Any?>, services[2], 2, false, false) as JLabel
        assertEquals("Bing", row.text)
    }

    @Test
    fun `choosing a row selects through the selector's callback as a translator`() {
        val calls = mutableListOf<Pair<ServiceRole, String>>()
        val chip = chip(calls)
        onEdt { chip.render(state()) }
        val list = listIn(chip.popupForTest()!!)

        onEdt {
            list.selectedIndex = 2
            list.actionMap.get("chooseService").actionPerformed(null)
        }
        assertEquals(listOf(ServiceRole.TRANSLATOR to "bing"), calls)
    }

    @Test
    fun `disabled while translating and when there is nothing to choose`() {
        val chip = chip()
        onEdt { chip.render(state(loading = true)) }
        assertFalse(chip.isEnabled)
        onEdt { chip.render(state(list = emptyList(), selected = null)) }
        assertFalse(chip.isEnabled)
        assertNull(chip.popupForTest())
    }

    @Test
    fun `mirrors in right to left`() {
        val chip = chip()
        onEdt {
            chip.componentOrientation = ComponentOrientation.RIGHT_TO_LEFT
            chip.render(state())
        }
        assertTrue(chip.insets.left > chip.insets.right, "the chevron's room is at the trailing edge, the left")
        assertEquals("DeepL", chip.text)
    }

    @Test
    fun `the list renderer is shared rather than copied`() {
        val selector = java.io.File(
            "src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/selector/TranslatorSelector.kt"
        ).readText()
        assertTrue(selector.contains("ServiceListCellRenderer(iconManager)"))
        assertFalse(selector.contains("class ServiceRenderer"))
    }
}

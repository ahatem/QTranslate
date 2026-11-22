package com.github.ahatem.qtranslate.ui.swing.main.languagebar

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.ui.swing.shared.TestIcons
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.LanguageComboBox
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Container
import java.nio.file.Files
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanguagePairChipTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-pair-chip-test").toFile(), LanguageTomlParser(), logger)
    }

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private val languages = listOf(LanguageCode.AUTO, LanguageCode.ENGLISH, LanguageCode.GERMAN, LanguageCode.ARABIC)

    private fun bar(
        source: LanguageCode = LanguageCode.ENGLISH,
        target: LanguageCode = LanguageCode.ARABIC,
        detected: LanguageCode? = null,
        loading: Boolean = false,
        canSwap: Boolean = true,
    ) = LanguageSelectionBarState(
        isLoading = loading,
        canClear = true,
        canSwap = canSwap,
        allSourceLanguages = languages,
        allTargetLanguages = languages.filter { it != LanguageCode.AUTO },
        selectedSourceLanguage = source,
        detectedSourceLanguage = detected,
        selectedTargetLanguage = target,
        strings = LanguageSelectionBarStrings("Translate", "Cancel", "Clear", "Swap languages"),
    )

    private class Calls {
        val sources = mutableListOf<LanguageCode>()
        val targets = mutableListOf<LanguageCode>()
        var swaps = 0
    }

    private fun chip(calls: Calls = Calls(), rtl: Boolean = false): LanguagePairChip {
        lateinit var chip: LanguagePairChip
        onEdt {
            chip = LanguagePairChip(
                iconManager = TestIcons.iconManager(),
                localizer = localizer,
                onSourceLanguageSelected = { calls.sources += it },
                onSwap = { calls.swaps++ },
                onTargetLanguageSelected = { calls.targets += it },
            )
            if (rtl) chip.componentOrientation = ComponentOrientation.RIGHT_TO_LEFT
        }
        return chip
    }

    private fun render(chip: LanguagePairChip, state: LanguagePairChipState) {
        onEdt { chip.render(state) }
        onEdt { } // the combo boxes fill themselves on a later EDT turn
    }

    private fun walk(c: Component): List<Component> =
        listOf(c) + if (c is Container) c.components.flatMap(::walk) else emptyList()

    private fun combos(root: Container) = walk(root).filterIsInstance<LanguageComboBox>()
    private fun swapOf(root: Container) =
        walk(root).filterIsInstance<AbstractButton>().single { it.toolTipText == "Swap languages" }

    @Test
    fun `the chip reads source to target with the codes in use`() {
        val chip = chip()
        render(chip, LanguagePairChipState(bar()))
        assertEquals("EN → AR", chip.text)
        assertTrue(chip.showChevron, "a chip that opens something shows the chevron")
    }

    @Test
    fun `an auto source shows the detected language, or auto until there is one`() {
        val chip = chip()
        render(chip, LanguagePairChipState(bar(source = LanguageCode.AUTO, detected = LanguageCode.GERMAN)))
        assertEquals("DE → AR", chip.text)
        render(chip, LanguagePairChipState(bar(source = LanguageCode.AUTO, detected = null)))
        assertEquals("AUTO → AR", chip.text)
    }

    @Test
    fun `right to left mirrors the arrow and puts the target first`() {
        val chip = chip(rtl = true)
        render(chip, LanguagePairChipState(bar()))
        // Button text is laid out left to right: written target first, the source is drawn at the
        // right and the arrow points from it to the target.
        assertEquals("AR ← EN", chip.text)
        assertFalse(chip.componentOrientation.isLeftToRight)
    }

    @Test
    fun `changing orientation after a render rewrites the pair`() {
        val chip = chip()
        render(chip, LanguagePairChipState(bar()))
        assertEquals("EN → AR", chip.text)
        onEdt { chip.componentOrientation = ComponentOrientation.RIGHT_TO_LEFT }
        assertEquals("AR ← EN", chip.text)
        onEdt { chip.componentOrientation = ComponentOrientation.LEFT_TO_RIGHT }
        assertEquals("EN → AR", chip.text)
    }

    @Test
    fun `the pair formatter is the one place the direction rule lives`() {
        assertEquals("A → B", formatLanguagePair("A", "B", rightToLeft = false))
        assertEquals("B ← A", formatLanguagePair("A", "B", rightToLeft = true))
    }

    @Test
    fun `the tooltip carries the full pair in the same direction`() {
        val chip = chip()
        val strings = LanguagePairChipStrings("Languages: %1\$s", "From", "To", "Auto-Detect")
        render(chip, LanguagePairChipState(bar(), strings))
        val tip = assertNotNull(chip.toolTipText)
        assertTrue(tip.startsWith("Languages: "), tip)
        assertTrue(tip.indexOf('→') > 0, tip)

        val rtl = chip(rtl = true)
        render(rtl, LanguagePairChipState(bar(), strings))
        assertTrue(assertNotNull(rtl.toolTipText).contains('←'))
    }

    @Test
    fun `the popup offers source, swap and target and draws the words it is handed`() {
        val chip = chip()
        val arabic = LanguagePairChipStrings("اللغات: %1\$s", "من", "إلى", "كشف تلقائي")
        render(chip, LanguagePairChipState(bar(), arabic))

        val popup = chip.popupForTest()
        val boxes = combos(popup)
        assertEquals(2, boxes.size, "source and target")
        swapOf(popup)
        val labels = walk(popup).filterIsInstance<JLabel>().map { it.text }
        assertEquals(listOf("من", "إلى"), labels)
        assertEquals(languages.size, boxes[0].itemCount)
        assertEquals(languages.size - 1, boxes[1].itemCount, "the target list has no Auto")
        assertEquals(LanguageCode.ENGLISH, boxes[0].selectedItem)
        assertEquals(LanguageCode.ARABIC, boxes[1].selectedItem)
    }

    @Test
    fun `the popup reports through the same callbacks the bar uses`() {
        val calls = Calls()
        val chip = chip(calls)
        render(chip, LanguagePairChipState(bar()))
        val popup = chip.popupForTest()
        val (source, target) = combos(popup)
        val swap = swapOf(popup)

        onEdt { source.selectedItem = LanguageCode.GERMAN }
        onEdt { target.selectedItem = LanguageCode.ENGLISH }
        onEdt { swap.doClick() }

        assertEquals(listOf(LanguageCode.GERMAN), calls.sources)
        assertEquals(listOf(LanguageCode.ENGLISH), calls.targets)
        assertEquals(1, calls.swaps)
    }

    @Test
    fun `rendering the popup is not a user choice`() {
        val calls = Calls()
        val chip = chip(calls)
        render(chip, LanguagePairChipState(bar()))
        render(chip, LanguagePairChipState(bar(source = LanguageCode.GERMAN, target = LanguageCode.ENGLISH)))
        assertTrue(calls.sources.isEmpty() && calls.targets.isEmpty(), "state changes must not echo back as intents")
    }

    @Test
    fun `while translating the chip and its controls follow the bar's rules`() {
        val chip = chip()
        render(chip, LanguagePairChipState(bar(loading = true)))
        assertFalse(chip.isEnabled)
        val popup = chip.popupForTest()
        assertTrue(combos(popup).none { it.isEnabled })
        assertFalse(swapOf(popup).isEnabled)

        render(chip, LanguagePairChipState(bar(canSwap = false)))
        assertTrue(chip.isEnabled)
        assertFalse(swapOf(popup).isEnabled)
    }

    @Test
    fun `the popup lays out mirrored in right to left`() {
        val chip = chip(rtl = true)
        render(chip, LanguagePairChipState(bar()))
        val popup = chip.popupForTest()
        onEdt {
            popup.applyComponentOrientation(chip.componentOrientation)
            popup.setSize(popup.preferredSize)
            fun layout(c: Container) {
                c.doLayout()
                c.components.forEach { if (it is Container) layout(it) }
            }
            layout(popup)
        }
        val (source, target) = combos(popup)
        assertTrue(source.x > target.x, "the source sits at the right in a right to left interface")
    }
}

package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the small but important boundary between transient floating windows and normal dialogs.
 * The actual foreground result still needs live Windows coverage.
 */
class FloatingPopupPolicyTest {

    private val sourceRoot = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing")

    private fun source(relativePath: String): String =
        File(sourceRoot, relativePath).readText()

    @Test
    fun `shared floating popup behavior excludes its dialog from application modality`() {
        val behavior = source("shared/widgets/FloatingPopupBehavior.kt")

        assertContains(
            behavior,
            "window.modalExclusionType = Dialog.ModalExclusionType.APPLICATION_EXCLUDE",
        )
        assertContains(behavior, "window.setAutoRequestFocus(false)")
    }

    @Test
    fun `selection button is excluded without becoming focusable`() {
        val button = source("main/SelectionTranslateButton.kt")

        assertContains(button, "modalExclusionType = Dialog.ModalExclusionType.APPLICATION_EXCLUDE")
        assertContains(button, "setAutoRequestFocus(false)")
        assertContains(button, "focusableWindowState = false")
    }

    @Test
    fun `loading indicator is excluded while retaining its lightweight window policy`() {
        val indicator = source("quicktranslate/LoadingIndicator.kt")

        assertContains(indicator, "modalExclusionType = Dialog.ModalExclusionType.APPLICATION_EXCLUDE")
        assertContains(indicator, "setAutoRequestFocus(false)")
        assertContains(indicator, "isAlwaysOnTop = true")
        assertContains(indicator, "focusableWindowState = false")
        assertContains(indicator, "type = Type.UTILITY")
    }

    @Test
    fun `all three interactive floating dialogs use the shared behavior`() {
        listOf(
            "quicktranslate/QuickTranslateDialog.kt",
            "dictionary/QuickDictionaryDialog.kt",
            "imagesearch/ImageSearchDialog.kt",
        ).forEach { relativePath ->
            assertContains(source(relativePath), "FloatingPopupBehavior(")
        }
    }

    @Test
    fun `normal settings dialog remains application modal and is not excluded`() {
        val settings = source("settings/SettingsDialog.kt")

        assertContains(settings, ") : JDialog(owner, \"Settings\", true) {")
        assertFalse(settings.contains("APPLICATION_EXCLUDE"))
        assertTrue(settings.contains("JDialog(owner"))
    }
}

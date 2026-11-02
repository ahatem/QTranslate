package com.github.ahatem.qtranslate.ui.swing.snippingtool

import com.github.ahatem.qtranslate.core.settings.data.OcrTranslationDestination
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The capture flow hides the main window before opening. Routing the result to the popup must not
 * bring the hidden main window back.
 */
class OcrTranslationWindowPolicyTest {

    @Test
    fun `only the main window destination presents the main window`() {
        assertFalse(shouldPresentMainWindow(OcrTranslationDestination.POPUP))
        assertTrue(shouldPresentMainWindow(OcrTranslationDestination.MAIN_WINDOW))
    }
}

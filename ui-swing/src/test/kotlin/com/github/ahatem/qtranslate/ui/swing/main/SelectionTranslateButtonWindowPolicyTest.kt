package com.github.ahatem.qtranslate.ui.swing.main

import java.awt.Dialog
import java.awt.Window
import javax.swing.JWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the native-window policy that prevents external selection from activating QTranslate. */
class SelectionTranslateButtonWindowPolicyTest {

    @Test
    fun `selection popup has no owner parameter`() {
        val constructors = SelectionTranslateButton::class.java.declaredConstructors

        assertEquals(1, constructors.size)
        assertFalse(
            Window::class.java in constructors.single().parameterTypes,
            "an owner would put the popup back in the main window's activation chain",
        )
        assertTrue(SelectionTranslateButton::class.java.superclass == JWindow::class.java)
    }

    @Test
    fun `popup policy excludes application modal dialogs and focus requests`() {
        assertEquals(
            Dialog.ModalExclusionType.APPLICATION_EXCLUDE,
            SelectionTranslateButton.MODAL_EXCLUSION_TYPE,
        )
        assertFalse(SelectionTranslateButton.AUTO_REQUEST_FOCUS)
    }
}
